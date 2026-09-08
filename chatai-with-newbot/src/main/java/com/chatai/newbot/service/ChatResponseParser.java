package com.chatai.newbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 上游模型响应解析器，负责协议归一化和 usage 提取，不承担网络调用与计费。
 */
@Component
public class ChatResponseParser {
    private static final Logger log = LoggerFactory.getLogger(ChatResponseParser.class);
    private final ObjectMapper objectMapper;

    /** 创建响应解析器。 */
    public ChatResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 从 OpenAI 或 Anthropic 非流式响应中提取正文。 */
    @SuppressWarnings("unchecked")
    public String extractNonStreamText(Map<String, Object> json, boolean anthropic) {
        try {
            if (anthropic) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) json.get("content");
                if (content != null && !content.isEmpty()) {
                    Object text = content.get(0).get("text");
                    return text instanceof String value ? value : null;
                }
            } else {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) json.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null && message.get("content") instanceof String value) return value;
                }
            }
        } catch (RuntimeException ignored) {
            // 厂商返回结构不完整时按无正文处理，由调用方决定降级策略。
        }
        return null;
    }

    /** 将 Anthropic SSE 事件翻译为前端使用的 OpenAI 兼容 chunk。 */
    @SuppressWarnings("unchecked")
    public List<String> transformAnthropicChunk(String chunk,
                                                AtomicReference<Map<String, Object>> usageRef) {
        List<String> results = new ArrayList<>();
        try {
            String json = stripSsePrefix(chunk);
            if (json.isEmpty() || json.startsWith("event:") || json.equals("[DONE]")) return results;

            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String type = (String) parsed.get("type");
            if (type == null) return results;

            switch (type) {
                case "message_start" -> captureAnthropicInputUsage(parsed, usageRef);
                case "content_block_delta" -> appendAnthropicContentDelta(parsed, results);
                case "message_delta" -> captureAnthropicOutputUsage(parsed, usageRef);
                case "message_stop" -> appendFinalUsage(results, usageRef.get());
                case "error" -> throw new UpstreamStreamException();
                default -> {
                    // content_block_start、content_block_stop、ping 等事件无需转发。
                }
            }
        } catch (UpstreamStreamException e) {
            throw e;
        } catch (Exception e) {
            log.trace("Anthropic chunk 解析跳过: {}", e.getMessage());
        }
        return results;
    }

    /** 标识上游流内业务失败，交由流式客户端统一脱敏、终止与结算。 */
    public static class UpstreamStreamException extends RuntimeException {
        /** 不携带厂商原始错误详情，避免暴露内部配置或凭据。 */
        public UpstreamStreamException() {
            super("上游返回流内错误事件");
        }
    }

    /** 从 OpenAI 兼容流式 chunk 中提取 usage。 */
    @SuppressWarnings("unchecked")
    public void extractUsage(String chunk, AtomicReference<Map<String, Object>> usageRef) {
        try {
            String json = stripSsePrefix(chunk);
            if (json.isEmpty() || json.equals("[DONE]")) return;
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
            if (usage != null) usageRef.set(usage);
        } catch (Exception ignored) {
            // 非 JSON 或不含 usage 的增量内容无需处理。
        }
    }

    /** 去除 SSE data 前缀并返回紧凑内容。 */
    private String stripSsePrefix(String chunk) {
        String json = chunk == null ? "" : chunk.trim();
        return json.startsWith("data: ") ? json.substring(6).trim() : json;
    }

    /** 归一化 Anthropic message_start 中的输入与缓存 Token。 */
    @SuppressWarnings("unchecked")
    private void captureAnthropicInputUsage(Map<String, Object> parsed,
                                            AtomicReference<Map<String, Object>> usageRef) {
        Map<String, Object> message = (Map<String, Object>) parsed.get("message");
        if (message == null || !(message.get("usage") instanceof Map<?, ?> rawUsage)) return;
        Map<String, Object> normalized = getOrCreateUsage(usageRef);
        Object inputTokens = rawUsage.get("input_tokens");
        if (inputTokens instanceof Number value) normalized.put("prompt_tokens", value.intValue());
        Object cacheRead = rawUsage.get("cache_read_input_tokens");
        if (cacheRead instanceof Number value) normalized.put("cached_tokens", value.intValue());
    }

    /** 将 Anthropic 文本或思考增量追加为 OpenAI delta。 */
    @SuppressWarnings("unchecked")
    private void appendAnthropicContentDelta(Map<String, Object> parsed, List<String> results) {
        Map<String, Object> delta = (Map<String, Object>) parsed.get("delta");
        if (delta == null) return;
        String deltaType = (String) delta.get("type");
        if ("text_delta".equals(deltaType) && delta.get("text") instanceof String text) {
            results.add(buildOpenAIChunk(text, null));
        } else if ("thinking_delta".equals(deltaType) && delta.get("thinking") instanceof String thinking) {
            results.add(buildOpenAIChunk(null, thinking));
        }
    }

    /** 归一化 Anthropic message_delta 中的输出 Token。 */
    private void captureAnthropicOutputUsage(Map<String, Object> parsed,
                                             AtomicReference<Map<String, Object>> usageRef) {
        if (!(parsed.get("usage") instanceof Map<?, ?> usage)) return;
        Object outputTokens = usage.get("output_tokens");
        if (outputTokens instanceof Number value) {
            getOrCreateUsage(usageRef).put("completion_tokens", value.intValue());
        }
    }

    /** 在流结束时追加统一 usage chunk。 */
    private void appendFinalUsage(List<String> results, Map<String, Object> usage) throws Exception {
        if (usage == null || usage.isEmpty()) return;
        results.add(objectMapper.writeValueAsString(Map.of("usage", usage)));
    }

    /** 构建 OpenAI 兼容格式的增量 JSON。 */
    private String buildOpenAIChunk(String content, String reasoningContent) {
        try {
            Map<String, Object> delta = new HashMap<>();
            if (content != null) delta.put("content", content);
            if (reasoningContent != null) delta.put("reasoning_content", reasoningContent);
            Map<String, Object> choice = new HashMap<>();
            choice.put("delta", delta);
            choice.put("index", 0);
            return objectMapper.writeValueAsString(Map.of("choices", Collections.singletonList(choice)));
        } catch (Exception e) {
            return "";
        }
    }

    /** 获取或创建本次流的 usage 聚合对象。 */
    private Map<String, Object> getOrCreateUsage(AtomicReference<Map<String, Object>> usageRef) {
        Map<String, Object> usage = usageRef.get();
        if (usage == null) {
            usage = new HashMap<>();
            usageRef.set(usage);
        }
        return usage;
    }
}
