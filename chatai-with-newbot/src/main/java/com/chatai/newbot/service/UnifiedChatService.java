package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一聊天服务 - 使用OpenAI兼容协议调用所有厂商的API
 * 支持不同厂商的思考模式参数差异
 */
@Service
public class UnifiedChatService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedChatService.class);
    /** 用户未自定义全局提示词时使用的系统默认提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";
    private final StorageManager storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UnifiedChatService(StorageManager storageService) {
        this.storageService = storageService;
    }

    public Flux<String> chat(ChatRequest request, String modelConfigId, UsageLog usageLog) {
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null) {
            return Flux.just("{\"error\":{\"message\":\"模型配置不存在\",\"type\":\"config_error\"}}");
        }
        if (!config.isEnabled()) {
            return Flux.just("{\"error\":{\"message\":\"该模型已被禁用\",\"type\":\"config_error\"}}");
        }

        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String modelId = config.getModelId();

        log.info("调用模型: {} ({}), API: {}, 思考模式: {}", config.getDisplayName(), modelId, apiUrl, request.isDeepThinking());

        // 构建OpenAI兼容的请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("stream", true);
        // 要求API在流式响应中返回usage数据（OpenAI兼容协议要求）
        // 解决Qwen、Kimi等厂商在流式模式下不返回token用量的问题
        Map<String, Object> streamOptions = new HashMap<>();
        streamOptions.put("include_usage", true);
        requestBody.put("stream_options", streamOptions);

        // 解析当前用户的全局提示词（System Prompt）：
        // 优先级最高，永远置于消息列表起始位置（system role），时间顺序上先于对话历史与当前用户输入。
        // 由后端统一注入，客户端无法伪造或遗漏，从而保证每次 API 调用都必然携带。
        String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        if (usageLog != null && usageLog.getUserId() != null) {
            User currentUser = storageService.getUserById(usageLog.getUserId());
            if (currentUser != null && currentUser.getSystemPrompt() != null
                    && !currentUser.getSystemPrompt().trim().isEmpty()) {
                systemPrompt = currentUser.getSystemPrompt().trim();
            }
        }

        // 构建消息列表（支持多模态：当消息含图片时，content转为数组格式）
        List<Object> messages = new ArrayList<>();
        // 1) 全局提示词始终占据 messages[0]（即便将来增加上下文截断/压缩也不会被挤掉）
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // 2) 追加对话历史与当前用户输入（忽略客户端自带的 system 消息，统一由后端注入）
        if (request.getMessages() != null) {
            for (NewBotMessage msg : request.getMessages()) {
                // 跳过客户端携带的 system 消息，避免与后端注入的全局提示词重复或冲突
                if ("system".equals(msg.getRole())) {
                    continue;
                }
                // 过滤掉content为空的assistant消息，避免API报400错误
                if ("assistant".equals(msg.getRole()) && (msg.getContent() == null || msg.getContent().trim().isEmpty())) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.getRole());
                // 多模态：当消息含图片时，content转为 OpenAI Vision 格式的数组
                if (msg.getImages() != null && !msg.getImages().isEmpty() && config.isSupportsMultimodal()) {
                    List<Map<String, Object>> contentParts = new ArrayList<>();
                    // 添加图片部分
                    for (String imageBase64 : msg.getImages()) {
                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image_url");
                        Map<String, String> imageUrl = new HashMap<>();
                        imageUrl.put("url", imageBase64);
                        imagePart.put("image_url", imageUrl);
                        contentParts.add(imagePart);
                    }
                    // 添加文本部分
                    if (msg.getContent() != null && !msg.getContent().trim().isEmpty()) {
                        Map<String, Object> textPart = new HashMap<>();
                        textPart.put("type", "text");
                        textPart.put("text", msg.getContent());
                        contentParts.add(textPart);
                    }
                    m.put("content", contentParts);
                } else {
                    m.put("content", msg.getContent());
                }
                messages.add(m);
            }
        }
        requestBody.put("messages", messages);

        // 温度参数
        if (request.getTemperature() > 0) {
            requestBody.put("temperature", request.getTemperature());
        }
        if (request.getMax_tokens() > 0) {
            requestBody.put("max_tokens", request.getMax_tokens());
        }

        // 根据不同厂商处理思考模式参数
        // 注意：很多模型(如DeepSeek、Qwen3等)默认思考模式为enabled，
        // 因此当用户不勾选思考模式时，必须显式禁用
        String thinkingParamType = config.getThinkingParamType();
        if (thinkingParamType == null || thinkingParamType.isEmpty()) {
            thinkingParamType = "default";
        }

        if (request.isDeepThinking() && config.isSupportsThinking()) {
            // 开启思考模式
            switch (thinkingParamType) {
                case "deepseek":
                    Map<String, Object> thinking = new HashMap<>();
                    thinking.put("type", "enabled");
                    requestBody.put("thinking", thinking);
                    requestBody.put("reasoning_effort", "high");
                    break;
                case "qwen":
                    requestBody.put("enable_thinking", true);
                    // 思考模式下不传temperature和top_p
                    requestBody.remove("temperature");
                    requestBody.remove("top_p");
                    break;
                case "kimi":
                    Map<String, Object> kimiThinking = new HashMap<>();
                    kimiThinking.put("type", "enabled");
                    requestBody.put("thinking", kimiThinking);
                    // Kimi K2系列思考模式下temperature强制为1.0，其他情况移除自定义值
                    if (modelId != null && modelId.startsWith("kimi-k2")) {
                        requestBody.put("temperature", 1.0);
                    } else {
                        requestBody.remove("temperature");
                    }
                    requestBody.remove("top_p");
                    break;
                case "doubao":
                case "zhipu":
                    Map<String, Object> commonThinking = new HashMap<>();
                    commonThinking.put("type", "enabled");
                    requestBody.put("thinking", commonThinking);
                    requestBody.remove("temperature");
                    requestBody.remove("top_p");
                    break;
                default:
                    break;
            }
            log.debug("思考模式参数类型: {}, 已开启思考模式", thinkingParamType);
        } else if (config.isSupportsThinking()) {
            // 显式禁用思考模式（很多模型默认为enabled，必须主动关闭）
            switch (thinkingParamType) {
                case "deepseek":
                    Map<String, Object> thinking = new HashMap<>();
                    thinking.put("type", "disabled");
                    requestBody.put("thinking", thinking);
                    break;
                case "qwen":
                    requestBody.put("enable_thinking", false);
                    break;
                case "kimi":
                    Map<String, Object> kimiThinkingOff = new HashMap<>();
                    kimiThinkingOff.put("type", "disabled");
                    requestBody.put("thinking", kimiThinkingOff);
                    // Kimi K2系列非思考模式下temperature强制为0.6
                    if (modelId != null && modelId.startsWith("kimi-k2")) {
                        requestBody.put("temperature", 0.6);
                    }
                    requestBody.remove("top_p");
                    break;
                case "doubao":
                case "zhipu":
                    Map<String, Object> commonThinking = new HashMap<>();
                    commonThinking.put("type", "disabled");
                    requestBody.put("thinking", commonThinking);
                    break;
                default:
                    break;
            }
            log.debug("思考模式参数类型: {}, 已显式禁用思考模式", thinkingParamType);
        }

        // 确保apiUrl以 /v1 或类似路径结尾，拼接 /chat/completions
        String fullUrl = apiUrl;
        if (!fullUrl.endsWith("/")) {
            fullUrl += "/";
        }
        fullUrl += "chat/completions";

        WebClient webClient = WebClient.builder()
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();

        // 用于从流式响应中提取 usage token 数据
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();

        return webClient
                .post()
                .uri(fullUrl)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> {
                    log.debug("收到chunk: {}", chunk.length() > 100 ? chunk.substring(0, 100) + "..." : chunk);
                    // 尝试从 chunk 中提取 usage 数据
                    extractUsage(chunk, usageRef);
                })
                .doOnComplete(() -> {
                    log.info("流式输出完成");
                    // 流结束后，将 token 数据写入 UsageLog
                    if (usageLog != null && usageRef.get() != null) {
                        updateUsageLog(usageLog, usageRef.get());
                    }
                })
                .onErrorResume(e -> {
                    String errorMsg = e.getMessage();
                    String displayMsg = errorMsg;
                    if (e instanceof WebClientResponseException) {
                        WebClientResponseException wce = (WebClientResponseException) e;
                        String responseBody = wce.getResponseBodyAsString();
                        log.error("API调用错误: {} | 状态码: {} | 响应体: {}", errorMsg, wce.getStatusCode(), responseBody);
                        // 尝试从响应体中提取更友好的错误消息
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> errorObj = (Map<String, Object>) parsed.get("error");
                            if (errorObj != null && errorObj.get("message") != null) {
                                displayMsg = wce.getStatusCode().value() + " - " + errorObj.get("message");
                            } else {
                                displayMsg = wce.getStatusCode().value() + " " + wce.getStatusText();
                            }
                        } catch (Exception parseEx) {
                            displayMsg = wce.getStatusCode().value() + " " + wce.getStatusText();
                        }
                    } else {
                        log.error("API调用错误: {}", errorMsg);
                    }
                    return Flux.just(String.format(
                            "{\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}",
                            displayMsg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                    ));
                });
    }

    /**
     * 从流式响应 chunk 中提取 usage 数据
     * OpenAI 兼容格式: {"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":3}}}
     */
    @SuppressWarnings("unchecked")
    private void extractUsage(String chunk, AtomicReference<Map<String, Object>> usageRef) {
        try {
            // 处理 SSE data: 前缀
            String json = chunk.trim();
            if (json.startsWith("data: ")) {
                json = json.substring(6).trim();
            }
            if (json.isEmpty() || json.equals("[DONE]")) return;

            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
            if (usage != null) {
                usageRef.set(usage);
                log.debug("提取到 usage 数据: {}", usage);
            }
        } catch (Exception e) {
            // 不是 JSON 或不含 usage，忽略
        }
    }

    /**
     * 将提取的 usage 数据写入 UsageLog 并持久化
     * 支持不同厂商的 usage 字段格式差异
     */
    @SuppressWarnings("unchecked")
    private void updateUsageLog(UsageLog usageLog, Map<String, Object> usage) {
        try {
            Object pt = usage.get("prompt_tokens");
            Object ct = usage.get("completion_tokens");
            if (pt != null) usageLog.setPromptTokens(((Number) pt).intValue());
            if (ct != null) usageLog.setCompletionTokens(((Number) ct).intValue());

            // 提取缓存 token: prompt_tokens_details.cached_tokens (DeepSeek等)
            Object details = usage.get("prompt_tokens_details");
            if (details instanceof Map) {
                Object cached = ((Map<String, Object>) details).get("cached_tokens");
                if (cached != null) usageLog.setCachedTokens(((Number) cached).intValue());
            }

            // 提取思考/推理 token: completion_tokens_details.reasoning_tokens
            // DeepSeek、Qwen、Kimi等支持思考模式的模型会返回此字段
            Object compDetails = usage.get("completion_tokens_details");
            if (compDetails instanceof Map) {
                Object reasoning = ((Map<String, Object>) compDetails).get("reasoning_tokens");
                if (reasoning != null) usageLog.setReasoningTokens(((Number) reasoning).intValue());
            }

            storageService.updateUsageLog(usageLog);
            log.info("已更新使用记录 token: prompt={}, completion={}, cached={}, reasoning={}",
                    usageLog.getPromptTokens(), usageLog.getCompletionTokens(), usageLog.getCachedTokens(), usageLog.getReasoningTokens());
        } catch (Exception e) {
            log.error("更新 usage log token 数据失败", e);
        }
    }
}
