package com.chatai.newbot.service;

import com.chatai.newbot.exception.ApiException;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 验证上下文与输出独立继承，以及最终协议预算的真实边界。 */
class OutputTokenPolicyTest {
    /** 上下文旧值 0/空继承全局；正数覆盖，输出设置不改变容量。 */
    @Test
    void contextWindow_独立继承全局和模型覆盖() {
        ModelConfig model = new ModelConfig();
        model.setMaxOutputTokens(65536);
        assertEquals(128000, OutputTokenPolicy.contextWindow(model, "128000"));
        model.setContextWindow(0);
        assertEquals(128000, OutputTokenPolicy.contextWindow(model, "128000"));
        model.setContextWindow(200000);
        assertEquals(200000, OutputTokenPolicy.contextWindow(model, "128000"));
        model.setContextWindow(null);
        assertEquals(32000, OutputTokenPolicy.contextWindow(model, "0"));
        assertEquals(32000, OutputTokenPolicy.contextWindow(model, "broken"));
    }

    /** 同一长输入在大容量模型不限时不再被旧 32000 预算压缩为短输出。 */
    @Test
    void resolveForBody_长上下文不限使用真实配置剩余量() {
        ModelConfig model = new ModelConfig();
        model.setContextWindow(128000);
        model.setMaxOutputTokens(0);
        Map<String, Object> body = Map.of("system", "system", "messages", List.of(
                Map.of("role", "user", "content", "a".repeat(104000))));
        int remaining = 128000 - (int) OutputTokenPolicy.estimateInput(body);
        assertTrue(remaining > 90000);
        assertEquals(remaining, OutputTokenPolicy.resolveForBody(new ChatRequest(), model, body, "8192", "32000"));
        model.setMaxOutputTokens(4096);
        assertEquals(4096, OutputTokenPolicy.resolveForBody(new ChatRequest(), model, body, "0", "32000"));
        model.setContextWindow(32000);
        assertEquals(32000 - OutputTokenPolicy.estimateInput(body),
                OutputTokenPolicy.resolveForBody(new ChatRequest(), model, body, "0", "32000"));
    }

    /** 最终系统提示、附件等占满容量时拒绝请求，不伪造至少 1 Token 的可用空间。 */
    @Test
    void resolveForBody_最终输入占满容量明确拒绝() {
        ModelConfig model = new ModelConfig();
        model.setContextWindow(100);
        assertThrows(ApiException.class, () -> OutputTokenPolicy.resolveForBody(new ChatRequest(), model,
                Map.of("system", "汉".repeat(100)), "16384", "32000"));
    }

    /** 两种协议的图片按相同固定预算估算，不计入巨大的 base64 字符串。 */
    @Test
    void estimateInput_两协议多模态图片预算一致() {
        for (String type : List.of("image", "image_url")) {
            var body = Map.<String, Object>of("messages", List.of(Map.of("role", "user", "content",
                    List.of(Map.of("type", type, "text", "base64".repeat(10000))))));
            assertEquals(1104, OutputTokenPolicy.estimateInput(body));
        }
    }
}
