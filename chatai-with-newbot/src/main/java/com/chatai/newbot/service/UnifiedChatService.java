package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.UsageLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * 统一聊天服务 - 支持 OpenAI 兼容协议 与 Anthropic 协议
 * 根据 ModelConfig.protocol 字段自动选择请求构建与流式解析策略
 * 支持不同厂商的思考模式参数差异
 */
@Service
public class UnifiedChatService {
    private static final Logger log = LoggerFactory.getLogger(UnifiedChatService.class);
    /** Anthropic Messages API 的协议版本请求头值。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private final StorageManager storageService;
    private final WebSearchService webSearchService;
    private final ContextBudgetService contextBudgetService;
    private final ObservabilityService observabilityService;
    private final ChatResponseParser responseParser;
    private final ChatContextAssembler contextAssembler;
    private final StreamingChatClient streamingChatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 共享 WebClient：所有厂商请求复用同一实例（鉴权头按请求设置），避免每次对话新建客户端与连接池 */
    private final WebClient sharedWebClient = WebClient.builder()
            .defaultHeader("Content-Type", "application/json")
            .codecs(configurer -> configurer
                    .defaultCodecs()
                    .maxInMemorySize(16 * 1024 * 1024))
            .build();

    /** 创建统一聊天服务并注入上游调用所需的协作者。 */
    public UnifiedChatService(StorageManager storageService, WebSearchService webSearchService,
                              ContextBudgetService contextBudgetService, ObservabilityService observabilityService,
                              ChatResponseParser responseParser, ChatContextAssembler contextAssembler,
                              StreamingChatClient streamingChatClient) {
        this.storageService = storageService;
        this.webSearchService = webSearchService;
        this.contextBudgetService = contextBudgetService;
        this.observabilityService = observabilityService;
        this.responseParser = responseParser;
        this.contextAssembler = contextAssembler;
        this.streamingChatClient = streamingChatClient;
    }

    /** 校验模型、构建上下文并路由流式调用，初始化失败时释放已登记的活跃请求。 */
    public Flux<String> chat(ChatRequest request, String modelConfigId, UsageLog usageLog) {
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        // 早退分支（配置缺失/禁用）按"前置拒绝"计数，与控制器层的权限/限流拒绝口径一致；
        // 这些分支不进入流式阶段，不计逻辑聊天请求数，也无需释放活跃流
        if (config == null) {
            observabilityService.chatRejected();
            return Flux.just("{\"error\":{\"message\":\"模型配置不存在\",\"type\":\"config_error\"}}");
        }
        if (!config.isEnabled()) {
            observabilityService.chatRejected();
            return Flux.just("{\"error\":{\"message\":\"该模型已被禁用\",\"type\":\"config_error\"}}");
        }

        // 联网搜索：用户开启且全局启用时，用最后一条用户消息检索，将结果作为参考资料注入
        String searchContext = null;
        if (request.isWebSearch() && webSearchService.isEnabled()) {
            searchContext = webSearchService.searchAsContext(contextAssembler.lastUserText(request.getMessages()));
        }

        // 长对话上下文预算管理：按模型上下文容量裁剪历史（替代单纯条数截断）。
        // 当前输入本身超限时明确报错，不静默截掉用户关键内容。
        String systemPrompt = contextAssembler.resolveSystemPrompt(request, usageLog);
        ContextBudgetService.BudgetResult budget = contextBudgetService.applyBudget(
                request.getMessages(), config, systemPrompt + (searchContext != null ? searchContext : ""),
                request.getMax_tokens());
        if (budget.inputOverLimit) {
            observabilityService.chatRejected();
            return Flux.just("{\"error\":{\"message\":\"当前输入内容已超出模型上下文容量（约 "
                    + budget.estimatedPromptTokens + " / " + budget.contextWindow
                    + " tokens），请精简输入、清除上下文或更换更大容量的模型\",\"type\":\"context_limit\"}}");
        }
        // 兼容兜底：条数上限仍然生效（取预算裁剪与条数上限的较小结果）
        List<NewBotMessage> limited = contextAssembler.applyMessageLimit(budget.messages);
        // 摘要注入：历史被裁剪且开启摘要时，注入此前历史的有效摘要（仅作背景参考，不覆盖原始指令）
        String summaryContext = null;
        if (budget.droppedCount > 0 && request.getChatId() != null && !request.getChatId().isEmpty()
                && usageLog != null && usageLog.getUserId() != null && isSummaryEnabled()) {
            summaryContext = storageService.getChatContextSummary(
                    usageLog.getUserId(), request.getChatId(), budget.droppedCount);
            if (summaryContext == null) {
                // 无有效摘要：异步生成供下次请求使用（本次降级为纯截断，不阻塞首 Token）
                scheduleSummaryGeneration(config, usageLog, request, budget.droppedCount);
            }
        }
        request.setMessages(limited);

        String protocol = config.getProtocol();
        // 逻辑聊天请求计数与计時起点：确定进入流式阶段才计数（前置校验拒绝不计）；
        // 活跃流计数在 decorateStream 的 doFinally 释放，覆盖正常/异常/取消/初始化失败全路径
        observabilityService.chatStarted();
        long startNanos = System.nanoTime();
        try {
            if ("anthropic".equalsIgnoreCase(protocol)) {
                return streamingChatClient.chatAnthropic(request, config, usageLog, searchContext, summaryContext, startNanos);
            }
            return streamingChatClient.chatOpenAI(request, config, usageLog, searchContext, summaryContext, startNanos);
        } catch (RuntimeException e) {
            // 此时客户端尚未返回装饰后的 Flux，必须在这里补齐终态，返回错误流供控制器释放预算。
            observabilityService.chatFinished(ObservabilityService.ChatOutcome.FAILED);
            log.error("流式请求初始化失败: {}", e.getClass().getSimpleName());
            return Flux.just("{\"error\":{\"message\":\"模型服务暂时不可用，请稍后重试或更换模型\",\"type\":\"api_error\"}}");
        }
    }

    /** 历史摘要功能开关（t_setting: context_summary_enabled，缺省开启，可在后台系统设置关闭） */
    private boolean isSummaryEnabled() {
        String val = storageService.getSetting("context_summary_enabled");
        return val == null || !"false".equals(val);
    }

    /**
     * 异步生成历史摘要并写入缓存（守护线程，失败静默降级为截断，不影响当前请求）。
     * 摘要记录来源覆盖条数（coveredCount），编辑/重新生成/清除上下文后因条数不匹配自动失效。
     * 摘要调用按现有计费规则记录 usage（modelName 后缀 ·摘要 便于区分）。
     */
    private void scheduleSummaryGeneration(ModelConfig config, UsageLog usageLog,
                                           ChatRequest request, int coveredCount) {
        List<NewBotMessage> full = request.getMessages();
        if (full == null || full.size() <= coveredCount || coveredCount <= 0) return;
        List<NewBotMessage> covered = new ArrayList<>(full.subList(0, coveredCount));
        String userId = usageLog.getUserId();
        String chatId = request.getChatId();
        Thread worker = new Thread(() -> {
            try {
                String summary = generateContextSummary(config, covered);
                if (summary != null && !summary.isEmpty()) {
                    storageService.saveChatContextSummary(userId, chatId, coveredCount, summary);
                }
            } catch (Exception e) {
                log.warn("生成会话上下文摘要失败（已降级为截断）: chatId={}", chatId, e);
            }
        });
        worker.setDaemon(true);
        worker.setName("context-summary-" + chatId);
        worker.start();
    }

    /**
     * 调用模型生成历史摘要（非流式最小请求）：保留关键事实、约束、决定与未完成事项。
     * 摘要文本明确标注为背景参考，不得视为高于原始指令的系统命令。
     */
    private String generateContextSummary(ModelConfig config, List<NewBotMessage> covered) {
        StringBuilder convo = new StringBuilder();
        for (NewBotMessage m : covered) {
            if (m == null || m.getContent() == null) continue;
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            String text = m.getContent();
            if (text.length() > 2000) text = text.substring(0, 2000) + "…";
            convo.append(role).append(": ").append(text).append("\n\n");
            if (convo.length() > 24000) break; // 摘要输入本身做上限保护
        }
        if (convo.length() == 0) return null;
        boolean anthropic = "anthropic".equalsIgnoreCase(config.getProtocol());
        String prompt = "请将以下对话历史压缩为一份简明摘要，保留关键事实、约束条件、已做出的决定和未完成事项，"
                + "不超过 500 字，直接输出摘要正文：\n\n" + convo;
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelId());
        body.put("stream", false);
        body.put("max_tokens", 800);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (!anthropic && config.isSupportsThinking()) {
            String type = config.getThinkingParamType() == null ? "default" : config.getThinkingParamType();
            switch (type) {
                case "qwen" -> body.put("enable_thinking", false);
                case "deepseek", "kimi", "doubao", "zhipu" ->
                        body.put("thinking", Map.of("type", "disabled"));
                default -> { }
            }
        }
        String fullUrl = config.getApiUrl();
        if (!fullUrl.endsWith("/")) fullUrl += "/";
        fullUrl += anthropic ? "messages" : "chat/completions";
        try {
            String resp = sharedWebClient.post()
                    .uri(fullUrl)
                    .headers(h -> applyAuthHeaders(h, config, anthropic))
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();
            if (resp == null) return null;
            Map<String, Object> json = objectMapper.readValue(resp, new TypeReference<Map<String, Object>>() {});
            // 解析 OpenAI/Anthropic 两种响应的正文
            String text = responseParser.extractNonStreamText(json, anthropic);
            if (text == null || text.trim().isEmpty()) return null;
            // 记录摘要调用用量（按现有计费规则）
            recordSummaryUsage(json, config);
            return text.trim();
        } catch (Exception e) {
            log.warn("摘要生成请求失败: {}", e.getMessage());
            return null;
        }
    }

    /** 记录摘要调用的 Token 用量（按现有计费规则入 t_usage_log，模型名加 ·摘要 后缀区分） */
    @SuppressWarnings("unchecked")
    private void recordSummaryUsage(Map<String, Object> json, ModelConfig config) {
        try {
            Object usageObj = json.get("usage");
            if (!(usageObj instanceof Map)) return;
            Map<String, Object> usage = (Map<String, Object>) usageObj;
            UsageLog logEntry = new UsageLog();
            logEntry.setModelId(config.getModelId());
            logEntry.setModelName(config.getDisplayName() + "·摘要");
            logEntry.setTimestamp(java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            logEntry.setPromptTokens(usage.get("prompt_tokens") instanceof Number n ? n.intValue()
                    : (usage.get("input_tokens") instanceof Number n2 ? n2.intValue() : 0));
            logEntry.setCompletionTokens(usage.get("completion_tokens") instanceof Number n ? n.intValue()
                    : (usage.get("output_tokens") instanceof Number n2 ? n2.intValue() : 0));
            storageService.addUsageLog(logEntry);
        } catch (Exception e) {
            log.warn("记录摘要用量失败（不影响摘要使用）: {}", e.getMessage());
        }
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

}
