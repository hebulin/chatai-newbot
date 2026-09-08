package com.chatai.newbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ChatResponseParser 的协议归一化测试。 */
class ChatResponseParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatResponseParser parser = new ChatResponseParser(objectMapper);

    /** 验证 OpenAI 与 Anthropic 非流式响应正文提取。 */
    @Test
    void extractNonStreamText_兼容两种协议() {
        Map<String, Object> openAi = Map.of("choices", List.of(Map.of("message", Map.of("content", "openai"))));
        Map<String, Object> anthropic = Map.of("content", List.of(Map.of("text", "anthropic")));

        assertEquals("openai", parser.extractNonStreamText(openAi, false));
        assertEquals("anthropic", parser.extractNonStreamText(anthropic, true));
    }

    /** 验证 Anthropic 文本增量与 Token 用量归一化。 */
    @Test
    @SuppressWarnings("unchecked")
    void transformAnthropicChunk_输出OpenAI兼容格式并汇总用量() throws Exception {
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        parser.transformAnthropicChunk("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":3}}}", usageRef);
        List<String> content = parser.transformAnthropicChunk("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}", usageRef);
        List<String> thinking = parser.transformAnthropicChunk("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"分析\"}}", usageRef);
        parser.transformAnthropicChunk("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}", usageRef);
        List<String> done = parser.transformAnthropicChunk("data: {\"type\":\"message_stop\"}", usageRef);

        Map<String, Object> contentJson = objectMapper.readValue(content.get(0), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) contentJson.get("choices");
        assertEquals("你好", ((Map<String, Object>) choices.get(0).get("delta")).get("content"));
        Map<String, Object> thinkingJson = objectMapper.readValue(thinking.get(0), Map.class);
        List<Map<String, Object>> thinkingChoices = (List<Map<String, Object>>) thinkingJson.get("choices");
        assertEquals("分析", ((Map<String, Object>) thinkingChoices.get(0).get("delta")).get("reasoning_content"));
        assertEquals(12, usageRef.get().get("prompt_tokens"));
        assertEquals(3, usageRef.get().get("cached_tokens"));
        assertEquals(5, usageRef.get().get("completion_tokens"));
        assertTrue(done.get(0).contains("\"usage\""));
    }

    /** 流内 error 必须向客户端终态装饰器传播，不能当无关事件忽略。 */
    @Test
    void transformAnthropicChunk_错误事件抛出脱敏异常() {
        assertThrows(ChatResponseParser.UpstreamStreamException.class, () ->
                parser.transformAnthropicChunk("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}",
                        new AtomicReference<>()));
    }

    /** 验证 OpenAI SSE usage 提取并忽略结束标记。 */
    @Test
    void extractUsage_识别Sse前缀() {
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        parser.extractUsage("data: {\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}", usageRef);
        parser.extractUsage("data: [DONE]", usageRef);

        assertEquals(7, usageRef.get().get("prompt_tokens"));
        assertEquals(2, usageRef.get().get("completion_tokens"));
    }
}
