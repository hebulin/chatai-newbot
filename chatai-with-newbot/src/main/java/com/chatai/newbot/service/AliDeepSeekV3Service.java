package com.chatai.newbot.service;

import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.common.Message;
import com.chatai.newbot.config.AIConfig;
import com.chatai.newbot.model.ChatRequest;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Service
public class AliDeepSeekV3Service {
    private static final Logger log = LoggerFactory.getLogger(AliDeepSeekV3Service.class);
    private final AIConfig aiConfig;
    private final Application application;

    public AliDeepSeekV3Service(AIConfig aiConfig) {
        log.info("Initializing AliChatService with API URL: {}", aiConfig.getAliApiUrl());
        this.aiConfig = aiConfig;
        this.application = new Application();
    }

    public Flux<String> chat(ChatRequest request) {
        log.info("ali 深度思考 with {}", request.getMessages());
        return Flux.<String>create(emitter -> {
            try {
                if (aiConfig.getAliApiKey() == null || aiConfig.getAliApiKey().isEmpty()) {
                    log.error("API Key not configured");
                    throw new IllegalStateException("API Key not configured");
                }

                // 构建消息列表
                List<Message> messages = new ArrayList<>();
                request.getMessages().forEach(msg -> {
                    log.info("Processing message - Role: {}, Content: {}", msg.getRole(),
                            msg.getContent().length() > 50 ? msg.getContent().substring(0, 50) + "..." : msg.getContent());
                    messages.add(Message.builder()
                            .role(msg.getRole())
                            .content(msg.getContent())
                            .build());
                });

                ApplicationParam param = ApplicationParam.builder()
                        .apiKey(aiConfig.getAliApiKey())
                        .appId(aiConfig.getAliAppId())
                        .messages(messages)
                        .incrementalOutput(true)
                        .hasThoughts(true)
                        .build();

                Flowable<ApplicationResult> result = application.streamCall(param);

                result.subscribe(
                        data -> {
                            String content = data.getOutput().getText();
                            if (content != null && !content.isEmpty()) {
                                log.info("收到响应 chunk: {}", content);
                                // 构建 JSON 响应 发送给客户端
                                String jsonResponse = String.format(
                                        "{\"id\":\"%s\",\"object\":\"chat.completion.chunk\",\"created\":%d," +
                                                "\"model\":\"deepseek-v3\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"%s\"}," +
                                                "\"finish_reason\":null}]}",
                                        data.getRequestId(),
                                        System.currentTimeMillis() / 1000L,
                                        content.replace("\"", "\\\"")
                                );
                                emitter.next(jsonResponse);
                            }
                        },
                        error -> {
                            log.error("Error during streaming: {}", error.getMessage(), error);
                            String errorResponse = String.format(
                                    "{\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}",
                                    error.getMessage().replace("\"", "\\\"")
                            );
                            emitter.next(errorResponse);
                            emitter.complete();
                        },
                        () -> {
                            log.info("Stream completed successfully");
                            emitter.next("[DONE]");
                            emitter.complete();
                        }
                );

            } catch (Exception e) {
                log.error("API Error: {}", e.getMessage(), e);
                String errorResponse = String.format(
                        "{\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}",
                        e.getMessage().replace("\"", "\\\"")
                );
                emitter.next(errorResponse);
                emitter.complete();
            }
        }).doOnCancel(() -> {
            log.info("Client connection closed");
        }).subscribeOn(Schedulers.boundedElastic());
    }
} 