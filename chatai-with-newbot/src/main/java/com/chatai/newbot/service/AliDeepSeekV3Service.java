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
        log.info("ali deepseek with {}", request.getMessages());
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

                result.blockingForEach(
                        data -> {
                            String content = data.getOutput().getText();
                            log.info("收到响应 chunk: {}", content);
                            if (null == content) {
                                emitter.next("[DONE]");
                            } else {
                                // 构建 JSON 响应 发送给客户端
                                String jsonResponse =
                                        "{\"id\":\"" + data.getRequestId() + "\",\"object\":\"chat.completion.chunk\",\"created\":" + (System.currentTimeMillis() / 1000L) + "," +
                                                "\"model\":\"" + request.getModel() + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}," +
                                                "\"finish_reason\":null}]}";
                                emitter.next(jsonResponse);
                            }
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