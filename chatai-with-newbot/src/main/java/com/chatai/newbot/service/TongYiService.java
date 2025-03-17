package com.chatai.newbot.service;

import com.chatai.newbot.config.AIConfig;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TongYiService {
    private static final Logger log = LoggerFactory.getLogger(TongYiService.class);
    private final AIConfig aiConfig;
    private final WebClient webClient;
    private static final String COMPATIBLE_MODE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public TongYiService(AIConfig aiConfig) {
        this.aiConfig = aiConfig;
        this.webClient = WebClient.builder()
                .baseUrl(COMPATIBLE_MODE_URL)
                .defaultHeader("Authorization", "Bearer " + aiConfig.getAliApiKey())
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 通义千问的普通模型
     * @param request
     * @return
     */
    public Flux<String> chat(ChatRequest request) {
        log.info("开始新的对话 - 模型: {}, 是否深度思考: {}", request.getModel(), request.isDeepThinking());
        List<Message> messages = request.getMessages();
        try {
            log.info(String.valueOf(messages.get(messages.size() - 1)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", request.getModel());
        requestBody.put("messages", messages);
        requestBody.put("stream", request.isStream());
        requestBody.put("temperature", request.getTemperature());
        requestBody.put("max_tokens", request.getMax_tokens());

        return webClient
                .post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
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
                    log.error("Error in TongYi chat request: {}", e.getMessage());
                    return Flux.error(e);
                });
    }
}