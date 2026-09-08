package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.UsageLog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** StreamingChatClient 的真实 HTTP/SSE 边界测试。 */
class StreamingChatClientTest {
    private HttpServer server;

    /** 每个用例结束后关闭本地 HTTP 服务。 */
    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** OpenAI SSE 完成时应透传数据、持久化一次调用并回填 usage。 */
    @Test
    void chatOpenAI_完成流只新增一次用量并回填Token() throws Exception {
        String response = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5," +
                "\"prompt_tokens_details\":{\"cached_tokens\":2}," +
                "\"completion_tokens_details\":{\"reasoning_tokens\":1}}}\n\n" +
                "data: [DONE]\n\n";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        StorageManager storage = mock(StorageManager.class);
        FileStorageService files = mock(FileStorageService.class);
        ChatContextAssembler context = mock(ChatContextAssembler.class);
        ObservabilityService observability = mock(ObservabilityService.class);
        when(context.resolveSystemPrompt(any(), any())).thenReturn("系统提示词");
        when(context.contentWithAttachments(any(), any(), any(Boolean.class)))
                .thenAnswer(invocation -> ((NewBotMessage) invocation.getArgument(0)).getContent());
        StreamingChatClient client = new StreamingChatClient(
                storage, files, context, observability, new ChatResponseParser(new com.fasterxml.jackson.databind.ObjectMapper()));

        UsageLog usage = new UsageLog();
        usage.setUserId("user-1");
        ModelConfig config = new ModelConfig();
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("test-key");
        config.setModelId("test-model");
        config.setDisplayName("测试模型");
        ChatRequest request = new ChatRequest();
        NewBotMessage message = new NewBotMessage();
        message.setRole("user");
        message.setContent("你好");
        request.setMessages(List.of(message));

        List<String> chunks = client.chatOpenAI(request, config, usage, null, null, System.nanoTime())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertFalse(chunks == null || chunks.isEmpty());
        // doFinally 在下游完成通知后执行，等待结算再读取可变 usage 字段。
        verify(observability, timeout(1000).times(1)).chatFinished(ObservabilityService.ChatOutcome.SUCCESS);
        assertEquals(12, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(2, usage.getCachedTokens());
        assertEquals(1, usage.getReasoningTokens());
        verify(storage, timeout(1000).times(1)).addUsageLog(usage);
        verify(storage, timeout(1000).times(1)).updateUsageLog(usage);
    }

    /** 用最小依赖替身创建真实流式客户端。 */
    private StreamingChatClient client(StorageManager storage, ObservabilityService observability) {
        ChatContextAssembler context = mock(ChatContextAssembler.class);
        when(context.resolveSystemPrompt(any(), any())).thenReturn("system-prompt");
        return new StreamingChatClient(storage, mock(FileStorageService.class), context, observability,
                new ChatResponseParser(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    /** 单独驱动两协议共用的终态装饰器，避免等待真实五分钟空闲窗口。 */
    private Flux<String> decorate(StreamingChatClient client, Flux<String> upstream, UsageLog usage) {
        return ReflectionTestUtils.invokeMethod(client, "decorateStream", upstream, usage,
                new AtomicReference<Map<String, Object>>(), System.nanoTime());
    }

    /** 输出前遇瞬态错误会重试，但只持久化一个逻辑调用。 */
    @Test
    void retry_首次失败第二次成功只结算一次() {
        StorageManager storage = mock(StorageManager.class);
        ObservabilityService observability = mock(ObservabilityService.class);
        UsageLog usage = new UsageLog();
        AtomicInteger attempts = new AtomicInteger();
        Flux<String> upstream = Flux.defer(() -> attempts.incrementAndGet() == 1
                ? Flux.error(new java.io.IOException("temporary")) : Flux.just("answer"));
        assertEquals(List.of("answer"), decorate(client(storage, observability), upstream, usage)
                .collectList().block(Duration.ofSeconds(5)));
        assertEquals(2, attempts.get());
        verify(storage, timeout(1000).times(1)).addUsageLog(usage);
        verify(observability, timeout(1000).times(1)).chatFinished(ObservabilityService.ChatOutcome.SUCCESS);
    }

    /** 已输出内容后超时不再重试，保留消耗并标为超时。 */
    @Test
    void timeout_输出后超时保留部分消耗且不重放内容() {
        StorageManager storage = mock(StorageManager.class);
        ObservabilityService observability = mock(ObservabilityService.class);
        UsageLog usage = new UsageLog();
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<String> upstream = Flux.concat(Flux.just("partial"), Flux.error(new java.util.concurrent.TimeoutException()))
                .doOnSubscribe(subscription -> subscriptions.incrementAndGet());
        List<String> result = decorate(client(storage, observability), upstream, usage)
                .collectList().block(Duration.ofSeconds(2));
        assertEquals(1, subscriptions.get());
        assertEquals("partial", result.get(0));
        assertTrue(result.get(1).contains("timeout_error"));
        verify(storage, timeout(1000).times(1)).addUsageLog(usage);
        verify(observability, timeout(1000).times(1)).chatFinished(ObservabilityService.ChatOutcome.TIMEOUT);
    }

    /** 取消流与未输出的业务错误使用不同结算规则。 */
    @Test
    void termination_取消只结算一次而无输出失败不计用量() {
        StorageManager storage = mock(StorageManager.class);
        ObservabilityService observability = mock(ObservabilityService.class);
        StreamingChatClient client = client(storage, observability);
        UsageLog cancelled = new UsageLog();
        cancelled.setRequestId("cancelled");
        decorate(client, Flux.never(), cancelled).subscribe().dispose();
        verify(storage).addUsageLog(cancelled);
        verify(observability).chatFinished(ObservabilityService.ChatOutcome.CANCELLED);

        UsageLog failed = new UsageLog();
        failed.setRequestId("failed");
        List<String> result = decorate(client, Flux.error(new IllegalArgumentException("invalid")), failed)
                .collectList().block(Duration.ofSeconds(2));
        assertTrue(result.get(0).contains("api_error"));
        verify(storage, never()).addUsageLog(failed);
        verify(observability, timeout(1000).times(1)).chatFinished(ObservabilityService.ChatOutcome.FAILED);
    }

    /** 两协议请求构建同步失败时仍返回错误流并释放活跃计数，不占用量。 */
    @Test
    void initialization_非法URL构建失败不泄漏活跃请求() {
        for (String protocol : List.of("openai", "anthropic")) {
            StorageManager storage = mock(StorageManager.class);
            ObservabilityService observability = mock(ObservabilityService.class);
            ChatContextAssembler context = mock(ChatContextAssembler.class);
            when(context.resolveSystemPrompt(any(), any())).thenReturn("system");
            when(context.applyMessageLimit(any())).thenReturn(List.of());
            ModelConfig config = new ModelConfig();
            config.setEnabled(true);
            config.setApiUrl("http://[invalid");
            config.setApiKey("test-key");
            config.setProtocol(protocol);
            when(storage.getModelConfigById("test")).thenReturn(config);
            ChatRequest request = new ChatRequest();
            request.setMessages(List.of());
            UnifiedChatService service = new UnifiedChatService(storage, mock(WebSearchService.class),
                    new ContextBudgetService(), observability,
                    new ChatResponseParser(new com.fasterxml.jackson.databind.ObjectMapper()), context,
                    client(storage, observability));
            UsageLog usage = new UsageLog();
            List<String> chunks = service.chat(request, "test", usage).collectList().block(Duration.ofSeconds(2));
            assertTrue(chunks.getFirst().contains("api_error"));
            verify(observability).chatStarted();
            verify(observability).chatFinished(ObservabilityService.ChatOutcome.FAILED);
            verify(storage, never()).addUsageLog(usage);
        }
    }

    /** HTTP 200 中的错误事件必须通知前端并记失败，仅已有正文时保留消耗。 */
    @Test
    void anthropic_流内错误不被误判成功且按是否输出结算() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String prefix = requests.incrementAndGet() == 1 ? "" :
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n";
            byte[] bytes = (prefix + "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"secret-detail\"}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        for (int attempt = 0; attempt < 2; attempt++) {
            StorageManager storage = mock(StorageManager.class);
            ObservabilityService observability = mock(ObservabilityService.class);
            ModelConfig config = new ModelConfig();
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setApiKey("test-key");
            config.setModelId("test-model");
            ChatRequest request = new ChatRequest();
            request.setMessages(List.of());
            UsageLog usage = new UsageLog();
            List<String> chunks = client(storage, observability)
                    .chatAnthropic(request, config, usage, null, null, System.nanoTime())
                    .collectList().block(Duration.ofSeconds(5));
            assertTrue(chunks.getLast().contains("api_error"));
            assertFalse(chunks.toString().contains("secret-detail"));
            assertEquals(attempt + 1, chunks.size());
            verify(observability, timeout(1000).times(1)).chatFinished(ObservabilityService.ChatOutcome.FAILED);
            verify(observability, never()).chatFinished(ObservabilityService.ChatOutcome.SUCCESS);
            if (attempt == 0) verify(storage, never()).addUsageLog(usage);
            else verify(storage).addUsageLog(usage);
        }
        assertEquals(2, requests.get());
    }

    /** Anthropic 真实 SSE 经客户端转换后保留正文、思考与输入输出用量。 */
    @Test
    void anthropic_真实事件流与鉴权请求保持兼容() throws Exception {
        String body = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reason\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n" +
                "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n";
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> posted = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        StorageManager storage = mock(StorageManager.class);
        ObservabilityService observability = mock(ObservabilityService.class);
        ModelConfig config = new ModelConfig();
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("test-key");
        config.setModelId("test-model");
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of());
        UsageLog usage = new UsageLog();
        List<String> chunks = client(storage, observability)
                .chatAnthropic(request, config, usage, null, null, System.nanoTime())
                .collectList().block(Duration.ofSeconds(5));
        assertEquals("test-key", apiKey.get());
        assertTrue(posted.get().contains("system-prompt"));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("reasoning_content")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("answer")));
        verify(storage, timeout(1000).times(1)).addUsageLog(usage);
        verify(storage, timeout(1000).times(1)).updateUsageLog(usage);
        assertEquals(12, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
    }
}
