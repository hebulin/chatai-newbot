package com.chatai.newbot.service.impl;

import com.chatai.newbot.config.AIConfig;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.service.OfficialDeepSeekV3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.stream.Collectors;

@Service
public class OfficialDeepSeekV3ServiceImpl implements OfficialDeepSeekV3Service {
    private final AIConfig aiConfig;
    private final WebClient webClient;
    private static final Logger log = LoggerFactory.getLogger(OfficialDeepSeekV3ServiceImpl.class);

    public OfficialDeepSeekV3ServiceImpl(AIConfig aiConfig) {
        this.aiConfig = aiConfig;

        // 验证 API URL
        if (!StringUtils.hasText(aiConfig.getDeepseekUrl())) {
            throw new IllegalStateException("AI API URL must not be empty");
        }

        this.webClient = WebClient.builder()
                .baseUrl(aiConfig.getDeepseekUrl())
                .defaultHeader("Authorization", "Bearer " + aiConfig.getDeepseekKey())
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)) // 增加缓冲区大小
                .build();
    }

    /**
     * 官方深度思考V3
     * @param request
     * @return
     */
    public Flux<String> chat(ChatRequest request) {
        request.setModel(request.getModel());
        log.info("官方深度思考V3: {}", request);

        // 过滤掉系统消息
        request.setMessages(request.getMessages().stream()
                .filter(msg -> !"system".equals(msg.getRole()) ||
                        "You are a helpful assistant.".equals(msg.getContent()))
                .collect(Collectors.toList()));

        return webClient
                .post()
                .uri(aiConfig.getDeepseekUrl())
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .map(error -> new RuntimeException("API Error: " + error)))
                .onStatus(HttpStatus::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .map(error -> new RuntimeException("Server Error: " + error)))
                .bodyToFlux(String.class)
                .map(chunk -> {
                    log.debug("收到原始响应: {}", chunk);
                    if (chunk.contains("content")) {
                        try {
                            // 解析 JSON 字符串
                            if (chunk.contains("delta") && chunk.contains("content")) {
                                int contentStart = chunk.indexOf("\"content\":\"") + 11;
                                int contentEnd = chunk.indexOf("\"", contentStart);
                                String content = chunk.substring(contentStart, contentEnd);
                                if (!content.isEmpty()) {
                                    log.info("AI响应内容: {}", content);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("解析响应内容失败: {}", e.getMessage());
                        }
                    } else if (chunk.contains("[DONE]")) {
                        log.info("对话完成");
                    }
                    return chunk;
                })
                .onErrorResume(e -> {
                    log.error("请求错误: {}", e.getMessage());
                    return Flux.just(String.format(
                            "data: {\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}\n\n",
                            e.getMessage().replace("\"", "\\\"")
                    ));
                })
                .doOnComplete(() -> log.info("Stream completed"));
    }

} 