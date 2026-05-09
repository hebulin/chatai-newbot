package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 统一聊天服务 - 使用OpenAI兼容协议调用所有厂商的API
 * 支持不同厂商的思考模式参数差异
 */
@Service
public class UnifiedChatService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedChatService.class);
    private final FileStorageService storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UnifiedChatService(FileStorageService storageService) {
        this.storageService = storageService;
    }

    public Flux<String> chat(ChatRequest request, String modelConfigId) {
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

        // 构建消息列表
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.getMessages() != null) {
            for (NewBotMessage msg : request.getMessages()) {
                Map<String, String> m = new HashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
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
                case "doubao":
                case "zhipu":
                    Map<String, Object> commonThinking = new HashMap<>();
                    commonThinking.put("type", "enabled");
                    requestBody.put("thinking", commonThinking);
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

        return webClient
                .post()
                .uri(fullUrl)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> log.debug("收到chunk: {}", chunk.length() > 100 ? chunk.substring(0, 100) + "..." : chunk))
                .onErrorResume(e -> {
                    log.error("API调用错误: {}", e.getMessage());
                    return Flux.just(String.format(
                            "{\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}",
                            e.getMessage().replace("\"", "\\\"")
                    ));
                })
                .doOnComplete(() -> log.info("流式输出完成"));
    }
}
