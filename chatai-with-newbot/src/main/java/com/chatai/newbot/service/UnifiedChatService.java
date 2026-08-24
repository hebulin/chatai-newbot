package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatAttachment;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.PromptPreset;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一聊天服务 - 支持 OpenAI 兼容协议 与 Anthropic 协议
 * 根据 ModelConfig.protocol 字段自动选择请求构建与流式解析策略
 * 支持不同厂商的思考模式参数差异
 */
@Service
public class UnifiedChatService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedChatService.class);
    /** 用户未自定义全局提示词时使用的系统默认提示词 */
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private final StorageManager storageService;
    private final FileStorageService fileStorageService;
    private final WebSearchService webSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 共享 WebClient：所有厂商请求复用同一实例（鉴权头按请求设置），避免每次对话新建客户端与连接池 */
    private final WebClient sharedWebClient = WebClient.builder()
            .defaultHeader("Content-Type", "application/json")
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(16 * 1024 * 1024))
            .build();

    /** 流式响应无数据超时：连续该时长未收到任何 chunk 时主动中断，避免厂商 API 卡死导致连接无限挂起 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);

    public UnifiedChatService(StorageManager storageService, FileStorageService fileStorageService,
                              WebSearchService webSearchService) {
        this.storageService = storageService;
        this.fileStorageService = fileStorageService;
        this.webSearchService = webSearchService;
    }

    public Flux<String> chat(ChatRequest request, String modelConfigId, UsageLog usageLog) {
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null) {
            return Flux.just("{\"error\":{\"message\":\"模型配置不存在\",\"type\":\"config_error\"}}");
        }
        if (!config.isEnabled()) {
            return Flux.just("{\"error\":{\"message\":\"该模型已被禁用\",\"type\":\"config_error\"}}");
        }

        // 联网搜索：用户开启且全局启用时，用最后一条用户消息检索，将结果作为参考资料注入
        String searchContext = null;
        if (request.isWebSearch() && webSearchService.isEnabled()) {
            searchContext = webSearchService.searchAsContext(lastUserText(request.getMessages()));
        }

        String protocol = config.getProtocol();
        if ("anthropic".equalsIgnoreCase(protocol)) {
            return chatAnthropic(request, config, usageLog, searchContext);
        }
        return chatOpenAI(request, config, usageLog, searchContext);
    }

    /** 取最后一条用户消息的纯文本内容（用作联网检索词） */
    private String lastUserText(List<NewBotMessage> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            NewBotMessage m = messages.get(i);
            if (m != null && "user".equals(m.getRole()) && m.getContent() != null
                    && !m.getContent().trim().isEmpty()) {
                return m.getContent().trim();
            }
        }
        return null;
    }

    // ==================== OpenAI 兼容协议 ====================

    private Flux<String> chatOpenAI(ChatRequest request, ModelConfig config, UsageLog usageLog, String searchContext) {
        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String modelId = config.getModelId();

        log.info("调用模型[OpenAI]: {} ({}), API: {}, 思考模式: {}", config.getDisplayName(), modelId, apiUrl, request.isDeepThinking());

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
        String systemPrompt = resolveSystemPrompt(request, usageLog);

        // 构建消息列表（支持多模态：当消息含图片时，content转为数组格式）
        List<Object> messages = new ArrayList<>();
        // 1) 全局提示词始终占据 messages[0]（即便将来增加上下文截断/压缩也不会被挤掉）
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // 1.5) 联网搜索参考资料：作为额外的 system 消息注入（RAG），紧随全局提示词之后
        if (searchContext != null && !searchContext.trim().isEmpty()) {
            Map<String, Object> searchMsg = new HashMap<>();
            searchMsg.put("role", "system");
            searchMsg.put("content", searchContext);
            messages.add(searchMsg);
        }

        // 2) 追加对话历史与当前用户输入（忽略客户端自带的 system 消息，统一由后端注入）
        // 上下文截断：仅保留最近 N 条（后端兜底，system 不占名额）
        List<NewBotMessage> historyMessages = applyContextLimit(request.getMessages());
        if (historyMessages != null) {
            for (NewBotMessage msg : historyMessages) {
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
                // 附件文档：解析文本合并进文本内容（纯文本方式，不依赖多模态）
                String textContent = contentWithAttachments(msg, usageLog.getUserId());
                // 多模态：当消息含图片时，content转为 OpenAI Vision 格式的数组
                if (msg.getImages() != null && !msg.getImages().isEmpty() && config.isSupportsMultimodal()) {
                    List<Map<String, Object>> contentParts = new ArrayList<>();
                    // 添加图片部分（本地上传 URL 先还原为 base64 data URL，存量 base64 原样透传）
                    for (String image : msg.getImages()) {
                        String imageBase64 = fileStorageService.toDataUrl(image, usageLog.getUserId());
                        if (imageBase64 == null) {
                            continue; // 本地文件已被删除，跳过该图片
                        }
                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image_url");
                        Map<String, String> imageUrl = new HashMap<>();
                        imageUrl.put("url", imageBase64);
                        imagePart.put("image_url", imageUrl);
                        contentParts.add(imagePart);
                    }
                    // 添加文本部分
                    if (textContent != null && !textContent.trim().isEmpty()) {
                        Map<String, Object> textPart = new HashMap<>();
                        textPart.put("type", "text");
                        textPart.put("text", textContent);
                        contentParts.add(textPart);
                    }
                    m.put("content", contentParts);
                } else {
                    m.put("content", textContent);
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

        // 用于从流式响应中提取 usage token 数据
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();

        Flux<String> stream = sharedWebClient
                .post()
                .uri(fullUrl)
                .header("Authorization", "Bearer " + apiKey)
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
                });
        // 统一装饰：无数据超时 + 未输出内容前瞬态重试 + 错误兜底 + 客户端取消日志
        return decorateStream(stream, usageLog, usageRef);
    }

    // ==================== Anthropic 协议 ====================

    private Flux<String> chatAnthropic(ChatRequest request, ModelConfig config, UsageLog usageLog, String searchContext) {
        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String modelId = config.getModelId();

        log.info("调用模型[Anthropic]: {} ({}), API: {}, 思考模式: {}", config.getDisplayName(), modelId, apiUrl, request.isDeepThinking());

        String systemPrompt = resolveSystemPrompt(request, usageLog);
        // 联网搜索参考资料：追加到 system 提示词之后（Anthropic 的 system 单独传参）
        if (searchContext != null && !searchContext.trim().isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + searchContext;
        }

        // 构建 Anthropic Messages API 请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelId);
        requestBody.put("stream", true);
        requestBody.put("system", systemPrompt);

        // max_tokens 在 Anthropic 中为必填
        int maxTokens = request.getMax_tokens() > 0 ? request.getMax_tokens() : 8192;

        // 构建消息列表（Anthropic 不允许 system role 在 messages 中）
        List<Object> messages = new ArrayList<>();
        // 上下文截断：仅保留最近 N 条（后端兜底，system 已单独传参）
        List<NewBotMessage> historyMessages = applyContextLimit(request.getMessages());
        if (historyMessages != null) {
            for (NewBotMessage msg : historyMessages) {
                if ("system".equals(msg.getRole())) {
                    continue;
                }
                if ("assistant".equals(msg.getRole()) && (msg.getContent() == null || msg.getContent().trim().isEmpty())) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.getRole());
                // 附件文档：解析文本合并进文本内容（纯文本方式，不依赖多模态）
                String textContent = contentWithAttachments(msg, usageLog.getUserId());
                // 多模态：Anthropic 图片格式为 {type:"image", source:{type:"base64",...}}
                if (msg.getImages() != null && !msg.getImages().isEmpty() && config.isSupportsMultimodal()) {
                    List<Map<String, Object>> contentParts = new ArrayList<>();
                    for (String image : msg.getImages()) {
                        // 本地上传 URL 先还原为 base64 data URL，存量 base64 原样透传
                        String imageBase64 = fileStorageService.toDataUrl(image, usageLog.getUserId());
                        if (imageBase64 == null) {
                            continue; // 本地文件已被删除，跳过该图片
                        }
                        Map<String, Object> imagePart = new HashMap<>();
                        imagePart.put("type", "image");
                        Map<String, Object> source = new HashMap<>();
                        source.put("type", "base64");
                        // 从 data URL 中提取 media_type 和纯 base64 数据
                        String mediaType = "image/png";
                        String base64Data = imageBase64;
                        if (imageBase64.startsWith("data:")) {
                            int commaIdx = imageBase64.indexOf(',');
                            if (commaIdx > 0) {
                                String header = imageBase64.substring(5, commaIdx); // e.g. "image/png;base64"
                                int semicolonIdx = header.indexOf(';');
                                if (semicolonIdx > 0) {
                                    mediaType = header.substring(0, semicolonIdx);
                                }
                                base64Data = imageBase64.substring(commaIdx + 1);
                            }
                        }
                        source.put("media_type", mediaType);
                        source.put("data", base64Data);
                        imagePart.put("source", source);
                        contentParts.add(imagePart);
                    }
                    if (textContent != null && !textContent.trim().isEmpty()) {
                        Map<String, Object> textPart = new HashMap<>();
                        textPart.put("type", "text");
                        textPart.put("text", textContent);
                        contentParts.add(textPart);
                    }
                    m.put("content", contentParts);
                } else {
                    m.put("content", textContent);
                }
                messages.add(m);
            }
        }
        requestBody.put("messages", messages);

        // 温度参数
        if (request.getTemperature() > 0) {
            requestBody.put("temperature", request.getTemperature());
        }

        // Anthropic 思考模式（Extended Thinking）
        if (request.isDeepThinking() && config.isSupportsThinking()) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            // budget_tokens 必须小于 max_tokens，设为 max_tokens 的 80%
            int budgetTokens = (int) (maxTokens * 0.8);
            thinking.put("budget_tokens", budgetTokens);
            requestBody.put("thinking", thinking);
            // Anthropic 思考模式下不允许设置 temperature
            requestBody.remove("temperature");
            requestBody.put("max_tokens", maxTokens);
            log.debug("Anthropic 思考模式已开启, budget_tokens={}", budgetTokens);
        } else {
            requestBody.put("max_tokens", maxTokens);
        }

        // 拼接 URL: /v1/messages
        String fullUrl = apiUrl;
        if (!fullUrl.endsWith("/")) {
            fullUrl += "/";
        }
        fullUrl += "messages";

        // 用于累计 Anthropic usage 数据
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();

        // Anthropic 使用 x-api-key 头认证
        Flux<String> stream = sharedWebClient
                .post()
                .uri(fullUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(chunk -> {
                    log.debug("Anthropic chunk: {}", chunk.length() > 200 ? chunk.substring(0, 200) + "..." : chunk);
                    List<String> transformed = transformAnthropicChunk(chunk, usageRef);
                    return Flux.fromIterable(transformed);
                })
                .doOnComplete(() -> {
                    log.info("Anthropic 流式输出完成");
                    if (usageLog != null && usageRef.get() != null) {
                        updateUsageLog(usageLog, usageRef.get());
                    }
                });
        // 统一装饰：无数据超时 + 未输出内容前瞬态重试 + 错误兜底 + 客户端取消日志
        return decorateStream(stream, usageLog, usageRef);
    }

    /**
     * 统一流式装饰器（OpenAI 与 Anthropic 共用）：
     * <ul>
     *   <li>无数据超时：连续 {@link #STREAM_IDLE_TIMEOUT} 未收到 chunk 时以 TimeoutException 中断，
     *       避免厂商 API 卡死（不断开连接也不发数据）导致 SSE 连接与服务端资源无限挂起</li>
     *   <li>瞬态重试：仅当尚未向客户端输出过任何内容时，对连接级瞬态错误（5xx/连接重置/超时）
     *       做有限退避重试（2 次，1s 起步）；已输出内容后不重试（避免内容重复）</li>
     *   <li>使用记录延迟写入：仅在流正常完成、客户端取消、或已输出内容后失败时才写入 UsageLog
     *       （这些场景厂商 API 已实际消耗）；请求直接失败（未产生任何输出）不写入、不占每日配额</li>
     *   <li>错误兜底：onErrorResume 转为前端可识别的 error JSON chunk</li>
     *   <li>取消日志：doOnCancel 记录客户端主动断开（前端中断/页面关闭），上游请求随之取消，
     *       不再继续消耗厂商 API 配额</li>
     * </ul>
     * 注意：doFinally 必须置于 retryWhen 之后、onErrorResume 之前——
     * 置于 retryWhen 之前会把重试用到的上游取消误判为终止信号；
     * 置于 onErrorResume 之后则错误已被转换为正常完成，无法区分成功与失败。
     * @param stream 原始厂商 SSE 流（含 doOnComplete 日志）
     * @param usageLog 本次调用的使用记录（由调用方构建但尚未入库；可为 null）
     * @param usageRef 累计 usage 数据引用
     * @return 装饰后的流
     */
    private Flux<String> decorateStream(Flux<String> stream, UsageLog usageLog,
                                        AtomicReference<Map<String, Object>> usageRef) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return stream
                .doOnNext(chunk -> emitted.set(true))
                .timeout(STREAM_IDLE_TIMEOUT)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(e -> !emitted.get() && isRetryable(e)))
                .doFinally(signal -> persistUsageOnTerminate(signal, usageLog, usageRef, emitted.get()))
                .onErrorResume(this::handleError)
                .doOnCancel(() -> log.info("客户端已断开SSE连接，上游请求已取消"));
    }

    /**
     * 流终止时按终止类型决定是否写入使用记录：
     * <ul>
     *   <li>ON_COMPLETE：正常完成，写入并回填 token 数据</li>
     *   <li>CANCEL：客户端取消（API 已实际消耗），写入并尽力回填已收到的部分 usage</li>
     *   <li>ON_ERROR 且已输出过内容：部分消耗已发生，写入并回填</li>
     *   <li>ON_ERROR 且未输出：请求失败未消耗，不写入、不占每日配额</li>
     * </ul>
     * @param signal 终止信号类型
     * @param usageLog 使用记录（null 时跳过）
     * @param usageRef 累计 usage 数据
     * @param emitted 是否已向客户端输出过内容
     */
    private void persistUsageOnTerminate(SignalType signal, UsageLog usageLog,
                                         AtomicReference<Map<String, Object>> usageRef, boolean emitted) {
        if (usageLog == null) {
            return;
        }
        boolean shouldPersist = signal == SignalType.ON_COMPLETE
                || signal == SignalType.CANCEL
                || (signal == SignalType.ON_ERROR && emitted);
        if (!shouldPersist) {
            log.info("流式请求失败且未产生输出，使用记录不写入（不占每日配额）: signal={}", signal);
            return;
        }
        try {
            storageService.addUsageLog(usageLog);
            if (usageRef.get() != null) {
                updateUsageLog(usageLog, usageRef.get());
            }
        } catch (Exception e) {
            log.error("终止阶段写入使用记录失败: signal={}", signal, e);
        }
    }

    /**
     * 判定异常是否为可安全重试的瞬态错误：
     * 5xx 响应、连接类异常（重置/拒绝/超时）；4xx 业务错误（鉴权失败/参数错误/限流）不重试
     * @param e 异常
     * @return true=可重试
     */
    private boolean isRetryable(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof WebClientResponseException wce) {
                return wce.getStatusCode().is5xxServerError();
            }
            if (cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 将 Anthropic SSE 事件翻译为前端可识别的 OpenAI 兼容格式
     * Anthropic 事件类型: message_start / content_block_delta / message_delta / message_stop 等
     */
    @SuppressWarnings("unchecked")
    private List<String> transformAnthropicChunk(String chunk, AtomicReference<Map<String, Object>> usageRef) {
        List<String> results = new ArrayList<>();
        try {
            String json = chunk.trim();
            // 去除 SSE 前缀
            if (json.startsWith("data: ")) {
                json = json.substring(6).trim();
            }
            // 跳过 event: 行和空行
            if (json.isEmpty() || json.startsWith("event:") || json.equals("[DONE]")) {
                return results;
            }

            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String type = (String) parsed.get("type");
            if (type == null) return results;

            switch (type) {
                case "message_start": {
                    // 提取初始 usage (input_tokens)
                    Map<String, Object> message = (Map<String, Object>) parsed.get("message");
                    if (message != null) {
                        Map<String, Object> usage = (Map<String, Object>) message.get("usage");
                        if (usage != null) {
                            Map<String, Object> normalized = getOrCreateUsage(usageRef);
                            Object inputTokens = usage.get("input_tokens");
                            if (inputTokens != null) {
                                normalized.put("prompt_tokens", ((Number) inputTokens).intValue());
                            }
                            Object cacheRead = usage.get("cache_read_input_tokens");
                            if (cacheRead != null) {
                                normalized.put("cached_tokens", ((Number) cacheRead).intValue());
                            }
                        }
                    }
                    break;
                }
                case "content_block_delta": {
                    Map<String, Object> delta = (Map<String, Object>) parsed.get("delta");
                    if (delta == null) break;
                    String deltaType = (String) delta.get("type");
                    if ("text_delta".equals(deltaType)) {
                        // 文本内容 → 转为 OpenAI choices[0].delta.content
                        String text = (String) delta.get("text");
                        if (text != null) {
                            results.add(buildOpenAIChunk(text, null));
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        // 思考内容 → 转为 OpenAI choices[0].delta.reasoning_content
                        String thinking = (String) delta.get("thinking");
                        if (thinking != null) {
                            results.add(buildOpenAIChunk(null, thinking));
                        }
                    }
                    break;
                }
                case "message_delta": {
                    // 提取输出 token 用量
                    Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
                    if (usage != null) {
                        Map<String, Object> normalized = getOrCreateUsage(usageRef);
                        Object outputTokens = usage.get("output_tokens");
                        if (outputTokens != null) {
                            normalized.put("completion_tokens", ((Number) outputTokens).intValue());
                        }
                    }
                    break;
                }
                case "message_stop": {
                    // 流结束，输出最终 usage 供前端提取
                    Map<String, Object> normalized = usageRef.get();
                    if (normalized != null && !normalized.isEmpty()) {
                        try {
                            Map<String, Object> usageChunk = new HashMap<>();
                            usageChunk.put("usage", normalized);
                            results.add(objectMapper.writeValueAsString(usageChunk));
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    break;
                }
                default:
                    // content_block_start, content_block_stop, ping 等忽略
                    break;
            }
        } catch (Exception e) {
            // 解析失败忽略（可能是 event: 行等非 JSON 内容）
            log.trace("Anthropic chunk 解析跳过: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 构建 OpenAI 兼容格式的 chunk JSON（供前端直接解析）
     */
    private String buildOpenAIChunk(String content, String reasoningContent) {
        try {
            Map<String, Object> delta = new HashMap<>();
            if (content != null) delta.put("content", content);
            if (reasoningContent != null) delta.put("reasoning_content", reasoningContent);

            Map<String, Object> choice = new HashMap<>();
            choice.put("delta", delta);
            choice.put("index", 0);

            Map<String, Object> chunk = new HashMap<>();
            chunk.put("choices", Collections.singletonList(choice));
            return objectMapper.writeValueAsString(chunk);
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> getOrCreateUsage(AtomicReference<Map<String, Object>> usageRef) {
        Map<String, Object> usage = usageRef.get();
        if (usage == null) {
            usage = new HashMap<>();
            usageRef.set(usage);
        }
        return usage;
    }

    // ==================== 公共方法 ====================

    /**
     * 解析当前会话生效的提示词：会话绑定的预设（内置智能体/用户角色）> 全局启用的预设 > 旧版单条提示词 > 默认提示词
     */
    private String resolveSystemPrompt(ChatRequest request, UsageLog usageLog) {
        String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        if (usageLog != null && usageLog.getUserId() != null) {
            User currentUser = storageService.getUserById(usageLog.getUserId());
            if (currentUser != null) {
                // 0) 会话绑定的预设优先：先匹配内置智能体（builtin- 前缀），再按 ID 精确匹配用户预设（不要求 enabled，绑定即生效）
                String presetId = request == null ? null : request.getPromptPresetId();
                String builtinContent = BuiltinAgents.contentById(presetId);
                if (builtinContent != null) {
                    return builtinContent;
                }
                if (presetId != null && !presetId.trim().isEmpty()
                        && currentUser.getPromptPresets() != null) {
                    for (PromptPreset p : currentUser.getPromptPresets()) {
                        if (p != null && presetId.equals(p.getId()) && p.getContent() != null
                                && !p.getContent().trim().isEmpty()) {
                            return p.getContent().trim();
                        }
                    }
                }
                // 1) 其次使用用户已启用的提示词预设（最多 1 条）
                if (currentUser.getPromptPresets() != null) {
                    for (PromptPreset p : currentUser.getPromptPresets()) {
                        if (p != null && p.isEnabled() && p.getContent() != null
                                && !p.getContent().trim().isEmpty()) {
                            return p.getContent().trim();
                        }
                    }
                }
                // 2) 兼容旧版单条全局提示词
                if (currentUser.getSystemPrompt() != null
                        && !currentUser.getSystemPrompt().trim().isEmpty()) {
                    systemPrompt = currentUser.getSystemPrompt().trim();
                }
            }
        }
        return systemPrompt;
    }

    /**
     * 上下文截断：仅保留最近 N 条消息（后端兜底，避免上下文无限增长）。
     * N 由全局配置 context_max_messages 控制，<=0 或消息数未超限时原样返回。
     * 后端注入的 system 消息不包含在此列表中，因此不占名额。
     * @param messages 客户端传入的完整消息列表
     * @return 截断后的消息列表
     */
    private List<NewBotMessage> applyContextLimit(List<NewBotMessage> messages) {
        if (messages == null) {
            return null;
        }
        int max = storageService.getContextMaxMessages();
        if (max <= 0 || messages.size() <= max) {
            return messages;
        }
        return messages.subList(messages.size() - max, messages.size());
    }

    /**
     * 将消息携带的附件文档内容合并进文本内容（附件在前、用户输入在后）。
     * 附件在上传时已解析为纯文本落盘，此处直接读回拼接，
     * 因此附件能力不依赖模型多模态，任意文本模型均可理解文档内容。
     * @param msg 待处理消息（无附件时原样返回 content）
     * @return 合并附件后的文本内容
     */
    private String contentWithAttachments(NewBotMessage msg, String userId) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        if (msg.getAttachments() == null || msg.getAttachments().isEmpty()) {
            return msg.getContent();
        }
        StringBuilder sb = new StringBuilder();
        for (ChatAttachment att : msg.getAttachments()) {
            if (att == null) {
                continue;
            }
            String name = att.getName() == null ? "未命名文档" : att.getName();
            String text = fileStorageService.readDocumentText(att.getUrl(), userId);
            sb.append("【附件文档：").append(name).append("】\n");
            sb.append(text != null ? text : "（该附件内容已失效，无法读取）");
            sb.append("\n【附件文档结束】\n\n");
        }
        sb.append(content);
        return sb.toString();
    }

    /**
     * AI 自动命名会话：基于首轮对话内容生成一个简短标题（非流式最小请求）。
     * 复用 testConnection 的同步 WebClient 模式：stream=false、小 max_tokens、短超时、思考显式关闭。
     * @param modelConfigId 模型配置ID
     * @param userContent 用户首条消息内容
     * @param assistantContent 助手首条回复内容（可为空）
     * @return 生成的标题（已去除引号/换行、截断长度）；失败返回 null
     */
    public String generateTitle(String modelConfigId, String userContent, String assistantContent) {
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        if (config.getApiUrl() == null || config.getApiUrl().trim().isEmpty()
                || config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            return null;
        }
        if (userContent == null || userContent.trim().isEmpty()) {
            return null;
        }

        boolean anthropic = "anthropic".equalsIgnoreCase(config.getProtocol());
        StringBuilder convo = new StringBuilder("用户: ").append(clip(userContent, 500));
        if (assistantContent != null && !assistantContent.trim().isEmpty()) {
            convo.append("\n助手: ").append(clip(assistantContent, 500));
        }
        String prompt = "请为以下对话生成一个不超过 15 个字的简短中文标题，只返回标题本身，不要加引号、标点或解释：\n\n" + convo;

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelId());
        body.put("stream", false);
        body.put("max_tokens", 64);
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", prompt);
        body.put("messages", List.of(msg));
        // 显式关闭思考，避免额外耗时与 token
        if (!anthropic && config.isSupportsThinking()) {
            String type = config.getThinkingParamType() == null ? "default" : config.getThinkingParamType();
            switch (type) {
                case "qwen" -> body.put("enable_thinking", false);
                case "deepseek", "kimi", "doubao", "zhipu" -> {
                    Map<String, Object> thinkingOff = new HashMap<>();
                    thinkingOff.put("type", "disabled");
                    body.put("thinking", thinkingOff);
                }
                default -> { }
            }
        }

        String fullUrl = config.getApiUrl();
        if (!fullUrl.endsWith("/")) {
            fullUrl += "/";
        }
        fullUrl += anthropic ? "messages" : "chat/completions";

        try {
            String resp = sharedWebClient.post()
                    .uri(fullUrl)
                    .headers(h -> applyAuthHeaders(h, config, anthropic))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            return parseTitleFromResponse(resp, anthropic);
        } catch (Exception e) {
            log.warn("AI 自动命名失败: {} ({}) -> {}", config.getDisplayName(), config.getModelId(), e.getMessage());
            return null;
        }
    }

    /** 从非流式应答中提取标题文本（OpenAI: choices[0].message.content；Anthropic: content[0].text） */
    @SuppressWarnings("unchecked")
    private String parseTitleFromResponse(String resp, boolean anthropic) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
            String text = null;
            if (anthropic) {
                Object contentObj = parsed.get("content");
                if (contentObj instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> first) {
                    Object t = ((Map<String, Object>) first).get("text");
                    text = t == null ? null : String.valueOf(t);
                }
            } else {
                Object choicesObj = parsed.get("choices");
                if (choicesObj instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> choice) {
                    Object messageObj = ((Map<String, Object>) choice).get("message");
                    if (messageObj instanceof Map<?, ?> message) {
                        Object c = ((Map<String, Object>) message).get("content");
                        text = c == null ? null : String.valueOf(c);
                    }
                }
            }
            if (text == null) {
                return null;
            }
            // 清理：去换行/首尾引号/多余空白，限长 30 字
            String title = text.replaceAll("[\\r\\n]+", " ").trim();
            title = title.replaceAll("^[\"'“「『]+|[\"'”」』]+$", "").trim();
            if (title.isEmpty()) {
                return null;
            }
            return clip(title, 30);
        } catch (Exception e) {
            return null;
        }
    }

    /** 截断字符串到指定最大长度 */
    private String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 按厂商协议设置鉴权头：Anthropic 用 x-api-key + anthropic-version，其余 OpenAI 兼容协议用 Bearer */
    private void applyAuthHeaders(org.springframework.http.HttpHeaders headers, ModelConfig config, boolean anthropic) {
        if (anthropic) {
            headers.set("x-api-key", config.getApiKey());
            headers.set("anthropic-version", ANTHROPIC_VERSION);
        } else {
            headers.set("Authorization", "Bearer " + config.getApiKey());
        }
    }

    /**
     * 模型连通性测试：向厂商 API 发一条最小非流式请求，验证 API Key/URL/模型ID 是否可用。
     * 连通成功后追加一次短生成请求测算生成速度（token/s），并将延迟/速度/测试时间持久化到模型配置。
     * 触发入口：管理员后台手动测试（AdminModelController）与模型健康检查定时任务（ModelHealthCheckService）。
     * @param modelConfigId 模型配置ID
     * @return success/message/latencyMs/speed，失败时 message 携带厂商返回的错误信息
     */
    public Map<String, Object> testConnection(String modelConfigId) {
        Map<String, Object> result = new HashMap<>();
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null) {
            result.put("success", false);
            result.put("message", "模型配置不存在");
            return result;
        }
        if (config.getApiUrl() == null || config.getApiUrl().trim().isEmpty()
                || config.getApiKey() == null || config.getApiKey().trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "API 地址或 API Key 未配置");
            return result;
        }

        boolean anthropic = "anthropic".equalsIgnoreCase(config.getProtocol());

        // 拼接端点：OpenAI 兼容协议 /chat/completions，Anthropic /messages
        String fullUrl = config.getApiUrl();
        if (!fullUrl.endsWith("/")) {
            fullUrl += "/";
        }
        fullUrl += anthropic ? "messages" : "chat/completions";

        long start = System.currentTimeMillis();
        try {
            // 第一次：最小请求（"hi" + max_tokens=16）测连通与延迟
            sharedWebClient.post()
                    .uri(fullUrl)
                    .headers(h -> applyAuthHeaders(h, config, anthropic))
                    .bodyValue(buildTestBody(config, anthropic, "hi", 16))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            long cost = System.currentTimeMillis() - start;

            // 第二次：短生成请求测速度（失败不影响连通结论，速度记为未知）
            Double speed = measureSpeed(fullUrl, config, anthropic);

            // 持久化测试指标（config 来自存储层含真实 apiKey，写回时会重新加密）
            config.setTestLatencyMs((int) cost);
            config.setTestSpeed(speed);
            config.setTestedAt(nowString());
            storageService.updateModelConfig(config);

            result.put("success", true);
            result.put("latencyMs", cost);
            result.put("speed", speed);
            String msg = "连接成功，延迟 " + cost + " ms";
            if (speed != null) {
                msg += "，速度 " + speed + " token/s";
            }
            result.put("message", msg);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            // 测试失败清空历史指标并记录测试时间，避免展示过期数据造成误导
            config.setTestLatencyMs(null);
            config.setTestSpeed(null);
            config.setTestedAt(nowString());
            storageService.updateModelConfig(config);
            String reason;
            // 剥离响应式异常包装，取出厂商真实错误
            Throwable cause = e;
            while (cause.getCause() != null && !(cause instanceof WebClientResponseException)) {
                cause = cause.getCause();
            }
            if (cause instanceof WebClientResponseException wce) {
                String respBody = wce.getResponseBodyAsString();
                reason = "HTTP " + wce.getStatusCode().value();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(respBody, Map.class);
                    Object errorObj = parsed.get("error");
                    if (errorObj instanceof Map && ((Map<?, ?>) errorObj).get("message") != null) {
                        reason += " - " + ((Map<?, ?>) errorObj).get("message");
                    } else if (respBody != null && !respBody.isEmpty()) {
                        reason += " - " + (respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
                    }
                } catch (Exception parseEx) {
                    if (respBody != null && !respBody.isEmpty()) {
                        reason += " - " + (respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
                    }
                }
            } else if (cause.getMessage() != null && cause.getMessage().contains("Timeout")) {
                reason = "连接超时（20 秒），请检查 API 地址是否可达";
            } else {
                reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            }
            log.warn("模型连通性测试失败: {} ({}) -> {}", config.getDisplayName(), config.getModelId(), reason);
            result.put("success", false);
            result.put("latencyMs", cost);
            result.put("message", reason);
        }
        return result;
    }

    /**
     * 构建连通测试用的非流式请求体（支持思考的模型显式关闭思考，降低测试成本）
     */
    private Map<String, Object> buildTestBody(ModelConfig config, boolean anthropic, String prompt, int maxTokens) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelId());
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", prompt);
        body.put("messages", List.of(msg));
        // 支持思考的模型需显式关闭思考（部分厂商非流式调用不允许开启思考，且避免消耗额外 token）
        if (!anthropic && config.isSupportsThinking()) {
            String type = config.getThinkingParamType() == null ? "default" : config.getThinkingParamType();
            switch (type) {
                case "qwen" -> body.put("enable_thinking", false);
                case "deepseek", "kimi", "doubao", "zhipu" -> {
                    Map<String, Object> thinkingOff = new HashMap<>();
                    thinkingOff.put("type", "disabled");
                    body.put("thinking", thinkingOff);
                }
                default -> { }
            }
        }
        return body;
    }

    /**
     * 测算生成速度：发一条短生成请求，用 usage 中的输出 token 数 / 总耗时估算 token/s。
     * 任一环节失败返回 null（速度未知），不影响连通测试结论。
     */
    private Double measureSpeed(String fullUrl, ModelConfig config, boolean anthropic) {
        try {
            String prompt = "请从1数到50，用逗号分隔，不要输出任何其他内容";
            long start = System.currentTimeMillis();
            String resp = sharedWebClient.post()
                    .uri(fullUrl)
                    .headers(h -> applyAuthHeaders(h, config, anthropic))
                    .bodyValue(buildTestBody(config, anthropic, prompt, 256))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            long elapsed = System.currentTimeMillis() - start;
            if (resp == null || elapsed <= 0) {
                return null;
            }
            // OpenAI 兼容: usage.completion_tokens；Anthropic: usage.output_tokens
            Map<String, Object> parsed = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
            Object usageObj = parsed.get("usage");
            if (!(usageObj instanceof Map)) {
                return null;
            }
            Object tokens = ((Map<?, ?>) usageObj).get(anthropic ? "output_tokens" : "completion_tokens");
            if (!(tokens instanceof Number) || ((Number) tokens).intValue() <= 0) {
                return null;
            }
            // token/s 保留一位小数
            return Math.round(((Number) tokens).intValue() * 10000.0 / elapsed) / 10.0;
        } catch (Exception e) {
            log.warn("模型测速失败（不影响连通结论）: {} ({}) -> {}",
                    config.getDisplayName(), config.getModelId(), e.getMessage());
            return null;
        }
    }

    /** 当前时间字符串（与存储层 createdAt 格式一致） */
    private String nowString() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 统一错误处理：厂商错误详情仅记录日志，返回给用户的消息做脱敏处理，
     * 避免泄露内部 API 地址、Key 片段等配置信息；流式超时给出友好提示
     */
    private Flux<String> handleError(Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException) {
            log.error("模型响应超时（{} 秒无数据），流已中断", STREAM_IDLE_TIMEOUT.getSeconds());
            return Flux.just("{\"error\":{\"message\":\"模型响应超时，请稍后重试或更换模型\",\"type\":\"timeout_error\"}}");
        }
        String errorMsg = e.getMessage();
        String displayMsg;
        if (e instanceof WebClientResponseException) {
            WebClientResponseException wce = (WebClientResponseException) e;
            String responseBody = wce.getResponseBodyAsString();
            log.error("API调用错误: {} | 状态码: {} | 响应体: {}", errorMsg, wce.getStatusCode(), responseBody);
            displayMsg = "模型服务异常（HTTP " + wce.getStatusCode().value() + "），请稍后重试或更换模型";
        } else {
            log.error("API调用错误: {}", errorMsg);
            displayMsg = "模型服务暂时不可用，请稍后重试或更换模型";
        }
        return Flux.just(String.format(
                "{\"error\":{\"message\":\"%s\",\"type\":\"api_error\"}}",
                displayMsg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        ));
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
     * 支持不同厂商的 usage 字段格式差异（OpenAI / Anthropic）
     */
    @SuppressWarnings("unchecked")
    private void updateUsageLog(UsageLog usageLog, Map<String, Object> usage) {
        try {
            Object pt = usage.get("prompt_tokens");
            Object ct = usage.get("completion_tokens");
            if (pt != null) usageLog.setPromptTokens(((Number) pt).intValue());
            if (ct != null) usageLog.setCompletionTokens(((Number) ct).intValue());

            // 提取缓存 token:
            // OpenAI格式: prompt_tokens_details.cached_tokens (DeepSeek等)
            // Anthropic格式(已归一化): 顶层 cached_tokens
            Object details = usage.get("prompt_tokens_details");
            if (details instanceof Map) {
                Object cached = ((Map<String, Object>) details).get("cached_tokens");
                if (cached != null) usageLog.setCachedTokens(((Number) cached).intValue());
            }
            Object cachedTop = usage.get("cached_tokens");
            if (cachedTop != null) usageLog.setCachedTokens(((Number) cachedTop).intValue());

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
