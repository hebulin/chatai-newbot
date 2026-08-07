package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.ChatHistoryService;
import com.chatai.newbot.service.RateLimitService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.UnifiedChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final UnifiedChatService chatService;
    private final StorageManager storageService;
    private final ChatHistoryService chatHistoryService;
    private final RateLimitService rateLimitService;

    public ChatController(UnifiedChatService chatService, StorageManager storageService,
                          ChatHistoryService chatHistoryService, RateLimitService rateLimitService) {
        this.chatService = chatService;
        this.storageService = storageService;
        this.chatHistoryService = chatHistoryService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat() {
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        User user = (User) httpRequest.getAttribute("currentUser");
        String modelConfigId = request.getModelConfigId();

        if (modelConfigId == null || modelConfigId.isEmpty()) {
            return Flux.just("{\"error\":{\"message\":\"未指定模型\",\"type\":\"param_error\"}}");
        }

        // 检查权限
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null) {
            return Flux.just("{\"error\":{\"message\":\"模型配置不存在\",\"type\":\"config_error\"}}");
        }

        if (!config.isEnabled()) {
            return Flux.just("{\"error\":{\"message\":\"该模型已被禁用\",\"type\":\"config_error\"}}");
        }

        // 权限检查（与 getVisibleModels 语义一致）：admin 不限；配置了 allowedModelIds（非空）
        // 的用户仅能使用白名单内模型；未配置则可使用所有公开（visibleToAll）模型
        if (!user.isAdmin()) {
            List<String> allowed = user.getAllowedModelIds();
            boolean permitted = (allowed != null && !allowed.isEmpty())
                    ? allowed.contains(config.getId())
                    : Boolean.TRUE.equals(config.getVisibleToAll());
            if (!permitted) {
                return Flux.just("{\"error\":{\"message\":\"无权使用该模型\",\"type\":\"permission_error\"}}");
            }
        }

        // 限流与配额检查（admin 豁免）
        if (!user.isAdmin()) {
            // 1) 每分钟短时限流（滑动窗口）
            int ratePerMinute = storageService.getRateLimitPerMinute();
            if (!rateLimitService.tryAcquire(user.getId(), ratePerMinute)) {
                return Flux.just("{\"error\":{\"message\":\"操作过于频繁，请稍后再试（每分钟最多 " + ratePerMinute + " 次）\",\"type\":\"rate_limit_error\"}}");
            }

            // 2) 每日限额：用户个人限额（次数/Token，二选一）优先，未设置则回退全局配额
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String personalType = user.getDailyLimitType();
            int personalValue = user.getDailyLimitValue();
            if ("count".equals(personalType) && personalValue > 0) {
                int used = storageService.countUsageByUserAndDay(user.getId(), today);
                if (used >= personalValue) {
                    return Flux.just("{\"error\":{\"message\":\"今日调用次数已达上限（" + personalValue + " 次），请明日再试\",\"type\":\"quota_error\"}}");
                }
            } else if ("token".equals(personalType) && personalValue > 0) {
                long usedTokens = storageService.sumTokensByUserAndDay(user.getId(), today);
                if (usedTokens >= personalValue) {
                    return Flux.just("{\"error\":{\"message\":\"今日 Token 用量已达上限（" + personalValue + "），请明日再试\",\"type\":\"quota_error\"}}");
                }
            } else {
                // 无个人限额，回退到全局每日调用次数配额
                int limit = storageService.getDailyChatLimit();
                if (limit > 0) {
                    int used = storageService.countUsageByUserAndDay(user.getId(), today);
                    if (used >= limit) {
                        return Flux.just("{\"error\":{\"message\":\"今日调用次数已达上限（" + limit + " 次），请明日再试\",\"type\":\"quota_error\"}}");
                    }
                }
            }
        }

        // 记录使用（仅构建对象，不立即入库）：由 UnifiedChatService 在流终止阶段
        // 按实际消耗情况写入——正常完成/客户端取消/已输出后失败才落库，
        // 请求直接失败（未产生任何输出）不写入、不占每日配额
        UsageLog usageLog = new UsageLog();
        usageLog.setUserId(user.getId());
        usageLog.setUsername(user.getUsername());
        usageLog.setModelId(config.getId());
        usageLog.setModelName(config.getDisplayName());
        usageLog.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        usageLog.setDeepThinking(request.isDeepThinking());

        return chatService.chat(request, modelConfigId, usageLog);
    }

    /**
     * 获取当前用户可用的模型列表
     */
    @GetMapping("/models")
    public Map<String, Object> getModels(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        List<ModelConfig> models = storageService.getVisibleModels(user);

        // 构建返回数据 - 不暴露apiKey（厂商名/图标同步已改为启动时执行，读接口不再写库）
        List<Map<String, Object>> modelList = new ArrayList<>();
        for (ModelConfig m : models) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId());
            item.put("displayName", m.getDisplayName());
            item.put("providerId", m.getProviderId());
            item.put("providerName", m.getProviderName());
            item.put("providerIcon", m.getProviderIcon());
            item.put("modelId", m.getModelId());
            item.put("supportsThinking", m.isSupportsThinking());
            item.put("supportsMultimodal", m.isSupportsMultimodal());
            item.put("thinkingParamType", m.getThinkingParamType());
            modelList.add(item);
        }

        result.put("success", true);
        result.put("data", modelList);
        result.put("defaultModelId", storageService.getDefaultModelId());
        // 联网搜索能力：全局开启且已配置 Key 时，聊天输入框才展示“联网”开关
        result.put("webSearchEnabled", storageService.getWebSearchEnabled()
                && storageService.getTavilyApiKey() != null && !storageService.getTavilyApiKey().trim().isEmpty());
        return result;
    }

    /**
     * 获取系统公告（登录用户可见，仅返回启用且处于公告期内的公告）
     * 返回: { "success": true, "id": "...", "title": "...", "content": "...", "updatedAt": "2026-07-29 10:00:00" }，无生效公告时 content 为空串
     */
    @GetMapping("/announcement")
    public Map<String, Object> getAnnouncement() {
        Map<String, Object> result = new HashMap<>();
        Announcement a = storageService.getActiveAnnouncement();
        result.put("success", true);
        result.put("id", a == null || a.getId() == null ? "" : a.getId());
        result.put("title", a == null || a.getTitle() == null ? "" : a.getTitle());
        result.put("content", a == null ? "" : a.getContent());
        result.put("updatedAt", a == null || a.getUpdatedAt() == null ? "" : a.getUpdatedAt());
        return result;
    }

    // ========== 会话历史同步（多端统一） ==========

    /**
     * 获取当前用户的会话历史（全量，含消息内容；供导出备份等场景使用）
     */
    @GetMapping("/chat/history")
    public Map<String, Object> getChatHistory(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> history = chatHistoryService.loadChatHistory(user.getId());
        history.put("success", true);
        return history;
    }

    /**
     * 获取当前用户的会话摘要列表（仅标题/预览/时间/条数，不含消息内容）
     * 懒加载模式的首屏接口：会话正文由前端切换会话时通过 /chat/history/single 按需拉取
     */
    @GetMapping("/chat/history/summary")
    public Map<String, Object> getChatHistorySummary(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = chatHistoryService.loadChatSummaries(user.getId());
        result.put("success", true);
        return result;
    }

    /**
     * 获取当前用户会话数据的版本号（多端自动同步的轻量变更检测）
     * 前端轮询/聊天页重新聚焦时先调此接口，版本未变化则不重拉摘要列表
     * 返回: { "success": true, "version": 1722300000000 }
     */
    @GetMapping("/chat/history/version")
    public Map<String, Object> getChatHistoryVersion(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("version", chatHistoryService.getChatHistoryVersion(user.getId()));
        return result;
    }

    /**
     * 保存当前用户的会话历史（增量合并：可只上传已加载的部分会话）
     * 请求体格式: { "lastChatId": "xxx", "chats": {...}, "chatMeta": {...}, "deletedChatIds": [...] }
     * 返回体携带保存后的 version，前端据此更新本地基准，避免自己的写入触发重拉
     */
    @PostMapping("/chat/history")
    public Map<String, Object> saveChatHistory(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        try {
            long version = chatHistoryService.saveChatHistory(user.getId(), body);
            result.put("success", true);
            result.put("version", version);
        } catch (Exception e) {
            log.error("保存会话历史失败: userId={}", user.getId(), e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取单个会话的最新记录（发送消息前的当前会话同步，避免拉全量历史）
     * 参数: chatId=会话ID；返回 { success, messages, meta }
     */
    @GetMapping("/chat/history/single")
    public Map<String, Object> getSingleChatHistory(@RequestParam String chatId,
                                                    HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> data = chatHistoryService.loadSingleChat(user.getId(), chatId);
            result.put("success", true);
            result.put("messages", data.get("messages"));
            result.put("meta", data.get("meta"));
        } catch (Exception e) {
            log.error("加载单会话历史失败: userId={}, chatId={}", user.getId(), chatId, e);
            result.put("success", false);
            result.put("message", "加载失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 跨会话全文搜索当前用户的会话消息
     * 参数: q=关键字；返回最多 50 条匹配（chatId/chatTitle/role/time/snippet）
     */
    @GetMapping("/chat/history/search")
    public Map<String, Object> searchChatHistory(@RequestParam(required = false) String q,
                                                 HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (q == null || q.trim().isEmpty()) {
            result.put("data", Collections.emptyList());
            return result;
        }
        result.put("data", chatHistoryService.searchChatHistory(user.getId(), q.trim(), 50));
        return result;
    }

    /**
     * 删除当前用户的所有会话历史
     */
    @DeleteMapping("/chat/history")
    public Map<String, Object> deleteChatHistory(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        try {
            chatHistoryService.deleteChatHistory(user.getId());
            result.put("success", true);
        } catch (Exception e) {
            log.error("删除会话历史失败: userId={}", user.getId(), e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

   // ========== 用户提示词预设（多条可自定义，最多启用 1 条） ==========

    /** 单条提示词内容上限 */
    private static final int PROMPT_CONTENT_MAX = 20000;
    /** 提示词名称上限 */
    private static final int PROMPT_TITLE_MAX = 50;
    /** 预设条数上限 */
    private static final int PROMPT_PRESET_MAX = 20;

    /**
     * 获取当前登录用户的提示词预设列表。
     * 兼容旧版：若预设为空但存在旧的单条 systemPrompt，则自动迁移为一条启用的预设。
     * @return { "success": true, "presets": [ { id, title, content, enabled } ], "builtinAgents": [...] }
     */
    @GetMapping("/user/prompt-presets")
    public Map<String, Object> getPromptPresets(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        User fresh = storageService.getUserById(user.getId());
        List<PromptPreset> presets = (fresh != null && fresh.getPromptPresets() != null)
                ? fresh.getPromptPresets() : new ArrayList<>();
        // 旧版单条全局提示词 → 迁移为一条启用预设
        if (fresh != null && presets.isEmpty()
                && fresh.getSystemPrompt() != null && !fresh.getSystemPrompt().trim().isEmpty()) {
            PromptPreset p = new PromptPreset();
            p.setId(UUID.randomUUID().toString());
            p.setTitle("默认提示词");
            p.setContent(fresh.getSystemPrompt().trim());
            p.setEnabled(true);
            presets = new ArrayList<>();
            presets.add(p);
            fresh.setPromptPresets(presets);
            fresh.setSystemPrompt(null);
            storageService.updateUser(fresh);
        }
        result.put("success", true);
        result.put("presets", presets);
        // 内置智能体：系统预设角色，所有用户可用，不可编辑、不占个人预设配额
        result.put("builtinAgents", com.chatai.newbot.service.BuiltinAgents.list());
        return result;
    }

    /**
     * 保存当前登录用户的提示词预设列表（整体替换）。
     * 请求体: { "presets": [ { id?, title, content, enabled } ] }。
     * 规则：最多 PROMPT_PRESET_MAX 条；最多启用 1 条（多余自动置为未启用）；空行忽略。
     */
    @PutMapping("/user/prompt-presets")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updatePromptPresets(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        Object raw = body.get("presets");
        List<PromptPreset> presets = new ArrayList<>();
        boolean enabledUsed = false;
        if (raw instanceof List<?> list) {
            if (list.size() > PROMPT_PRESET_MAX) {
                result.put("success", false);
                result.put("message", "提示词条数过多（最多 " + PROMPT_PRESET_MAX + " 条）");
                return result;
            }
            for (Object item : list) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                String title = m.get("title") instanceof String s ? s.trim() : "";
                String content = m.get("content") instanceof String s ? s.trim() : "";
                // 标题与内容均为空的行直接忽略
                if (title.isEmpty() && content.isEmpty()) continue;
                if (content.length() > PROMPT_CONTENT_MAX) {
                    result.put("success", false);
                    result.put("message", "单条提示词过长（最多 " + PROMPT_CONTENT_MAX + " 字符）");
                    return result;
                }
                if (title.isEmpty()) title = "未命名提示词";
                if (title.length() > PROMPT_TITLE_MAX) title = title.substring(0, PROMPT_TITLE_MAX);
                boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
                if (enabled && enabledUsed) enabled = false; // 最多启用 1 条
                if (enabled) enabledUsed = true;
                PromptPreset p = new PromptPreset();
                String id = m.get("id") instanceof String s ? s.trim() : "";
                // 禁止占用内置智能体 ID 前缀，避免与系统预设角色冲突
                if (id.startsWith("builtin-")) id = "";
                p.setId(id.isEmpty() ? UUID.randomUUID().toString() : id);
                p.setTitle(title);
                p.setContent(content);
                p.setEnabled(enabled);
                presets.add(p);
            }
        }
        User fresh = storageService.getUserById(user.getId());
        if (fresh == null) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }
        fresh.setPromptPresets(presets);
        fresh.setSystemPrompt(null); // 预设已为唯一来源，清除旧版单条提示词
        storageService.updateUser(fresh);
        result.put("success", true);
        result.put("message", "提示词已保存");
        result.put("presets", presets);
        return result;
    }

    /**
     * AI 自动命名会话。
     * 请求体: { "modelConfigId": "xxx", "userContent": "...", "assistantContent": "..." }
     * 返回: { "success": true, "title": "..." }；生成失败时 success=false（前端自行回退）
     */
    @PostMapping("/chat/generate-title")
    public Map<String, Object> generateTitle(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        String modelConfigId = body.get("modelConfigId") instanceof String s ? s.trim() : "";
        String userContent = body.get("userContent") instanceof String s ? s : "";
        String assistantContent = body.get("assistantContent") instanceof String s ? s : "";
        if (modelConfigId.isEmpty() || userContent.isEmpty()) {
            result.put("success", false);
            result.put("message", "参数不完整");
            return result;
        }
        // 权限校验：模型存在、启用且用户可见
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null || !config.isEnabled()) {
            result.put("success", false);
            result.put("message", "模型不可用");
            return result;
        }
        // 权限判定与 /chat 语义保持一致：admin 不限；配置了非空白名单的用户仅限白名单内模型；
        // 未配置白名单则可用所有公开模型。注意 allowedModelIds 可能为 null，需先判空防 NPE。
        if (!user.isAdmin()) {
            List<String> allowed = user.getAllowedModelIds();
            boolean permitted = (allowed != null && !allowed.isEmpty())
                    ? allowed.contains(config.getId())
                    : Boolean.TRUE.equals(config.getVisibleToAll());
            if (!permitted) {
                result.put("success", false);
                result.put("message", "无权使用该模型");
                return result;
            }
            // 短时限流：标题生成同样调用模型 API，纳入每分钟滑动窗口，避免绕过限流
            int ratePerMinute = storageService.getRateLimitPerMinute();
            if (!rateLimitService.tryAcquire(user.getId(), ratePerMinute)) {
                result.put("success", false);
                result.put("message", "操作过于频繁，请稍后再试");
                return result;
            }
        }
        try {
            String title = chatService.generateTitle(modelConfigId, userContent, assistantContent);
            if (title == null || title.isEmpty()) {
                result.put("success", false);
                result.put("message", "生成失败");
            } else {
                result.put("success", true);
                result.put("title", title);
            }
        } catch (Exception e) {
            log.warn("AI 生成会话标题失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "生成失败");
        }
        return result;
    }
}

