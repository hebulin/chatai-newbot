package com.chatai.newbot.service;

import com.chatai.newbot.config.AIConfig;
import com.chatai.newbot.model.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Map;

import static com.chatai.newbot.constant.AiConstant.*;

@Service
public class TongYiService {
    private static final Logger log = LoggerFactory.getLogger(TongYiService.class);
    private final AIConfig aiConfig;
    private final WebClient webClient;

    public TongYiService(AIConfig aiConfig) {
        this.aiConfig = aiConfig;

        // 默认初始化 WebClient
        this.webClient = WebClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .defaultHeader("Authorization", "Bearer " + aiConfig.getModelConfig("tongyi-turbo").get("api-key"))
                .build();
    }

    public Flux<String> chat(ChatRequest request) {
        // 动态加载模型配置
        Map<String, String> modelConfig = aiConfig.getModelConfig(request.getModel());
        String apiKey = modelConfig.get(API_KEY);
        String apiUrl = modelConfig.get(API_URL);
        String appId = modelConfig.get(APP_ID);

        // 动态初始化 WebClient
        WebClient dynamicWebClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        log.info("开始新的对话 - 模型: {}, 是否深度思考: {}", request.getModel(), request.isDeepThinking());

        return dynamicWebClient
                .post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> {
                    log.debug("收到原始响应: {}", chunk);
                    return chunk;
                });
    }

    public Flux<String> chatWithDeepThink(ChatRequest request) {
        // 动态加载模型配置
        Map<String, String> modelConfig = aiConfig.getModelConfig(request.getModel());
        String apiKey = modelConfig.get(API_KEY);
        String apiUrl = modelConfig.get(API_URL);
        String appId = modelConfig.get(APP_ID);

        // 动态初始化 WebClient
        WebClient dynamicWebClient = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();

        log.info("开始新的对话 - 模型: {}, 是否深度思考: {}", request.getModel(), request.isDeepThinking());

        return dynamicWebClient
                .post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> {
                    log.debug("收到原始响应: {}", chunk);
                    return chunk;
                });
    }
}