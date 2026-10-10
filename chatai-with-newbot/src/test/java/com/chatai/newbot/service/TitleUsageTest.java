package com.chatai.newbot.service;

import com.chatai.newbot.controller.ChatController;
import com.chatai.newbot.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 标题调用的真实协议计费、每日配额拒绝与异常释放预占。 */
class TitleUsageTest {
    /** OpenAI/Anthropic 成功请求保存用户、配置 ID、缓存 Token 和人民币费用。 */
    @Test
    void bothProtocolsRecordUsageAndCost() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String response = exchange.getRequestURI().getPath().endsWith("messages")
                    ? "{\"content\":[{\"type\":\"text\",\"text\":\"标题\"}],\"usage\":{\"input_tokens\":80,\"cache_read_input_tokens\":20,\"output_tokens\":8}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"标题\"}}],\"usage\":{\"prompt_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":20},\"completion_tokens\":8}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            StorageManager storage = mock(StorageManager.class);
            ModelConfig model = new ModelConfig(); model.setId("config"); model.setModelId("upstream");
            model.setEnabled(true); model.setApiKey("test"); model.setInputPriceCny(1); model.setCachedPriceCny(0.5); model.setOutputPriceCny(2);
            model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            when(storage.getModelConfigById("config")).thenReturn(model);
            UnifiedChatService service = new UnifiedChatService(storage, mock(WebSearchService.class), new ContextBudgetService(),
                    mock(ObservabilityService.class), new ChatResponseParser(new ObjectMapper()), mock(ChatContextAssembler.class), mock(StreamingChatClient.class));
            for (String protocol : new String[]{"openai", "anthropic"}) {
                model.setProtocol(protocol);
                UsageLog log = new UsageLog(); log.setUserId("user"); log.setModelId("config"); log.setRequestId(protocol);
                assertEquals("标题", service.generateTitle("config", "问题", "回答", log));
                assertEquals(100, log.getPromptTokens()); assertEquals(20, log.getCachedTokens()); assertEquals(8, log.getCompletionTokens());
                assertEquals(0.000106, log.getCostCny(), 0.000000001);
            }
            verify(storage, times(2)).addUsageLog(any());
        } finally { server.stop(0); }
    }

    /** 次数、Token、金额分别达到上限时不调用模型；异常结束后释放在途预占。 */
    @Test
    void titleHonorsDailyBudgetsAndReleasesOnFailure() {
        StorageManager storage = mock(StorageManager.class);
        UnifiedChatService service = mock(UnifiedChatService.class);
        ModelConfig model = new ModelConfig(); model.setId("config"); model.setEnabled(true); model.setVisibleToAll(true);
        model.setDisplayName("模型"); model.setInputPriceCny(1); model.setOutputPriceCny(2);
        when(storage.getModelConfigById("config")).thenReturn(model);
        when(service.estimateTitleInputTokens(anyString(), anyString())).thenReturn(1000);
        RateLimitService rate = mock(RateLimitService.class); when(rate.tryAcquire(anyString(), anyInt())).thenReturn(true);
        BudgetReservationService budget = spy(new BudgetReservationService(storage));
        ChatController controller = new ChatController(service, storage, mock(ChatHistoryService.class), rate, budget,
                mock(SvgAvatarService.class), mock(ObservabilityService.class));
        User user = new User(); user.setId("u"); user.setUsername("name"); user.setRole("user");
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setAttribute("currentUser", user);
        Map<String, Object> body = Map.of("modelConfigId", "config", "userContent", "问题");
        when(storage.getDailyChatLimit()).thenReturn(1);
        when(storage.countUsageByUserAndDay(anyString(), anyString())).thenReturn(1);
        assertEquals(false, controller.generateTitle(body, request).get("success"));
        when(storage.countUsageByUserAndDay(anyString(), anyString())).thenReturn(0);
        when(storage.getDailyTokenLimit()).thenReturn(1063L);
        assertEquals(false, controller.generateTitle(body, request).get("success"));
        when(storage.getDailyTokenLimit()).thenReturn(0L);
        when(storage.getDailyCostLimitCny()).thenReturn(0.00001);
        assertEquals(false, controller.generateTitle(body, request).get("success"));
        verify(service, never()).generateTitle(anyString(), anyString(), anyString(), any());
        when(storage.getDailyCostLimitCny()).thenReturn(0D);
        when(service.generateTitle(anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("upstream failed"));
        assertEquals(false, controller.generateTitle(body, request).get("success"));
        assertEquals(false, controller.generateTitle(body, request).get("success"));
        verify(budget, times(2)).release(anyString());
        var usage = ArgumentCaptor.forClass(UsageLog.class);
        verify(service, times(2)).generateTitle(eq("config"), anyString(), anyString(), usage.capture());
        assertEquals("u", usage.getValue().getUserId());
        assertEquals("config", usage.getValue().getModelId());
        assertEquals("模型·标题", usage.getValue().getModelName());
        assertNotNull(usage.getValue().getRequestId());
    }
}
