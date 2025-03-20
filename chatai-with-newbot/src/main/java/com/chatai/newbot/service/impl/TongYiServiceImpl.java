package com.chatai.newbot.service.impl;

import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.common.Message;
import com.chatai.newbot.config.AIConfig;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.service.TongYiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.chatai.newbot.constant.AiConstant.*;

@Service
public class TongYiServiceImpl implements TongYiService {
    private static final Logger log = LoggerFactory.getLogger(AliDeepSeekV3ServiceImpl.class);
    private AIConfig aiConfig;
    private Application application;

    public TongYiServiceImpl(AIConfig aiConfig) {
        log.info("Initializing AliChatService with API URL: {}", aiConfig.getAliApiUrl());
        this.aiConfig = aiConfig;
        this.application = new Application();
    }

    /**
     * 普通模型
     * @param request
     * @return
     */
    public Flux<String> chat(ChatRequest request) {
        String model = request.getModel();
        log.info("当前模型{}", model);
        Map<String, String> modelConfig = aiConfig.getModelConfig(model);
        String apiKey = modelConfig.get(API_KEY);
        String appId = modelConfig.get(APP_ID);

        return Flux.<String>create(emitter -> {
            try {
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
                        .apiKey(apiKey)
                        .appId(appId)
                        .messages(messages)
                        .incrementalOutput(true)
                        .hasThoughts(true)
                        .build();

                Flowable<ApplicationResult> result = application.streamCall(param);

                result.subscribe(
                        data -> {
                            String content = data.getOutput().getText();
                            String finishReason = data.getOutput().getFinishReason();
                            if (content != null && !content.isEmpty() && !finishReason.equals("stop")) {
                                log.info(model + "收到响应 chunk: {}", content);
                                // 构建 JSON 响应 发送给客户端
                                String jsonResponse =
                                        generateJsonString(data.getRequestId(), model, content);
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


    /**
     * 深度思考
     * @param request
     * @return
     */
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

    /**
     * 生成json字符串
     * @param id
     * @param model
     * @param content
     * @return
     * @throws Exception
     */
    private String generateJsonString(String id, String model, String content) throws Exception {
        // 创建一个ObjectMapper对象
        ObjectMapper objectMapper = new ObjectMapper();

        // 创建一个表示JSON结构的Map
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> choices = new HashMap<>();
        Map<String, Object> delta = new HashMap<>();

        // 设置delta的内容
        delta.put("content", content);
        delta.put("finish_reason", null);
        delta.put("index", 0);
        delta.put("logprobs", null);

        // 设置choices的内容
        choices.put("delta", delta);

        // 设置root的内容
        root.put("choices", new ArrayList<Object>() {{
            add(choices);
        }});
        root.put("object", "chat.completion.chunk");
        root.put("usage", null);
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("system_fingerprint", null);
        root.put("model", model);
        root.put("id", id);

        // 将Map转换为JSON字符串
        return objectMapper.writeValueAsString(root);
    }

}