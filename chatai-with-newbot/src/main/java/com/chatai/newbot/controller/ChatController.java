package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.ChatHistoryService;
import com.chatai.newbot.service.BudgetReservationService;
import com.chatai.newbot.service.RateLimitService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.UnifiedChatService;
import com.chatai.newbot.service.SvgAvatarService;
import com.chatai.newbot.service.ObservabilityService;
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
    private final BudgetReservationService budgetReservationService;
    private final SvgAvatarService svgAvatarService;
    private final ObservabilityService observabilityService;

    /** 注入聊天、历史、预算预占、头像校验和运行指标服务。 */
    public ChatController(UnifiedChatService chatService, StorageManager storageService,
                          ChatHistoryService chatHistoryService, RateLimitService rateLimitService,
                          BudgetReservationService budgetReservationService,
                          SvgAvatarService svgAvatarService,
                          ObservabilityService observabilityService) {
        this.chatService = chatService;
        this.storageService = storageService;
        this.chatHistoryService = chatHistoryService;
        this.rateLimitService = rateLimitService;
        this.budgetReservationService = budgetReservationService;
        this.svgAvatarService = svgAvatarService;
        this.observabilityService = observabilityService;
    }

    @GetMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat() {
        return storageService.isReady()
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(503).build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        User user = (User) httpRequest.getAttribute("currentUser");
        String modelConfigId = request.getModelConfigId();

        // 前置拒绝统一计数（未进入流式阶段的逻辑聊天请求）
        if (modelConfigId == null || modelConfigId.isEmpty()) {
            observabilityService.chatRejected();
            return Flux.just("{\"error\":{\"message\":\"未指定模型\",\"type\":\"param_error\"}}");
        }

        // 检查权限
        ModelConfig config = storageService.getModelConfigById(modelConfigId);
        if (config == null) {
            observabilityService.chatRejected();
            return Flux.just("{\"error\":{\"message\":\"模型配置不存在\",\"type\":\"config_error\"}}");
        }

        if (!config.isEnabled()) {
            observabilityService.chatRejected();
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
                observabilityService.chatRejected();
                return Flux.just("{\"error\":{\"message\":\"无权使用该模型\",\"type\":\"permission_error\"}}");
            }
        }

        String requestId = UUID.randomUUID().toString();
        // 限流与预算预占（admin 豁免）
        if (!user.isAdmin()) {
            // 1) 每分钟短时限流（滑动窗口）
            int ratePerMinute = storageService.getRateLimitPerMinute();
            if (!rateLimitService.tryAcquire(user.getId(), ratePerMinute)) {
                observabilityService.chatRejected();
                return Flux.just("{\"error\":{\"message\":\"操作过于频繁，请稍后再试（每分钟最多 " + ratePerMinute + " 次）\",\"type\":\"rate_limit_error\"}}");
            }

            BudgetReservationService.ReservationResult reservation =
                    budgetReservationService.reserve(user, request, config);
            if (!reservation.allowed()) {
                observabilityService.chatRejected();
                return Flux.just("{\"error\":{\"message\":\"" + reservation.message()
                        + "\",\"type\":\"quota_error\"}}");
            }
            requestId = reservation.requestId();
        }

        // 记录使用（仅构建对象，不立即入库）：由 UnifiedChatService 在流终止阶段
        // 按实际消耗情况写入——正常完成/客户端取消/已输出后失败才落库，
        // 请求直接失败（未产生任何输出）不写入、不占每日配额
        UsageLog usageLog = new UsageLog();
        usageLog.setRequestId(requestId);
        usageLog.setUserId(user.getId());
        usageLog.setUsername(user.getUsername());
        usageLog.setModelId(config.getId());
        usageLog.setModelName(config.getDisplayName());
        usageLog.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        usageLog.setDeepThinking(request.isDeepThinking());

        String finalRequestId = requestId;
        // 逻辑聊天请求计数在 UnifiedChatService 确定进入流式阶段时记录（前置拒绝不计），
        // 终态分类（成功/失败/取消/超时）由其在流真实终止点记录，避免
        // onErrorResume 把上游异常转成正常 SSE 错误消息后漏记失败
        return chatService.chat(request, modelConfigId, usageLog)
                .doFinally(signal -> {
                    budgetReservationService.release(finalRequestId);
                });
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
            // 上下文容量（Token）：前端展示预计上下文占用用；0/null 表示未配置（按默认 32000 估算）
            item.put("contextWindow", m.getContextWindow());
            modelList.add(item);
        }

        result.put("success", true);
        result.put("data", modelList);
        result.put("defaultModelId", storageService.getDefaultModelId());
        result.put("botAvatarSvg", storageService.getBotAvatarSvg());
        // 联网搜索能力：全局开启且已配置 Key 时，聊天输入框才展示“联网”开关
        result.put("webSearchEnabled", storageService.getWebSearchEnabled()
                && storageService.getTavilyApiKey() != null && !storageService.getTavilyApiKey().trim().isEmpty());
        return result;
    }

    /**
     * 获取当前用户可维护的个人资料与头像。
     */
    @GetMapping("/user/profile")
    public Map<String, Object> getUserProfile(HttpServletRequest request) {
        User current = (User) request.getAttribute("currentUser");
        User user = storageService.getUserById(current.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", toProfileMap(user));
        return result;
    }

    /**
     * 更新当前用户个人资料；用户名、角色、配额等安全字段不在本接口修改。
     */
    @PutMapping("/user/profile")
    public Map<String, Object> updateUserProfile(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        User current = (User) request.getAttribute("currentUser");
        User user = storageService.getUserById(current.getId());
        applyProfile(user, body);
        storageService.updateUser(user);
        return Map.of("success", true, "message", "个人资料已保存", "data", toProfileMap(user));
    }

    /**
     * 将用户资料转换为不含凭据与安全配置的公开 Map。
     */
    private Map<String, Object> toProfileMap(User user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", user.getUsername());
        profile.put("displayName", valueOrEmpty(user.getDisplayName()));
        profile.put("email", valueOrEmpty(user.getEmail()));
        profile.put("phone", valueOrEmpty(user.getPhone()));
        profile.put("department", valueOrEmpty(user.getDepartment()));
        profile.put("jobTitle", valueOrEmpty(user.getJobTitle()));
        profile.put("bio", valueOrEmpty(user.getBio()));
        profile.put("avatarType", valueOrEmpty(user.getAvatarType()).isEmpty() ? "default" : user.getAvatarType());
        profile.put("avatarValue", valueOrEmpty(user.getAvatarValue()));
        return profile;
    }

    /**
     * 从请求体应用有限长度的用户资料，并对 SVG 代码执行安全检查。
     */
    private void applyProfile(User user, Map<String, Object> body) {
        user.setDisplayName(profileText(body, "displayName", 80));
        user.setEmail(profileText(body, "email", 160));
        user.setPhone(profileText(body, "phone", 40));
        user.setDepartment(profileText(body, "department", 100));
        user.setJobTitle(profileText(body, "jobTitle", 100));
        user.setBio(profileText(body, "bio", 500));
        String avatarType = profileText(body, "avatarType", 20);
        if ("svg".equals(avatarType)) {
            user.setAvatarType("svg");
            user.setAvatarValue(svgAvatarService.sanitize(profileText(body, "avatarValue", 20_000)));
        } else {
            user.setAvatarType("default");
            user.setAvatarValue("");
        }
    }

    /** 读取并限制个人资料文本字段长度。 */
    private String profileText(Map<String, Object> body, String key, int maxLength) {
        String value = body != null && body.get(key) != null ? String.valueOf(body.get(key)).trim() : "";
        if (value.length() > maxLength) throw new IllegalArgumentException(key + " 字段过长");
        return value;
    }

    /** 将 null 转为空字符串，保持前端表单类型稳定。 */
    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
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
     * 保存当前用户的会话历史（增量合并 + 服务端原子版本校验：可只上传已变更的会话）
     * 请求体格式: { "lastChatId": "xxx", "chats": {...}, "chatMeta": {...}, "deletedChatIds": [...],
     *              "baseVersions": {chatId: 版本}, "baseFoldersVersion": n, "restoreChatIds": [...], "folders": [...] }
     * 返回体携带保存后的 version（全局同步序列号）、每会话新版本 versions、冲突明细 conflicts、
     * 文件夹版本 foldersVersion；冲突项不被覆盖，由前端提示用户选择保留哪一端
     */
    @PostMapping("/chat/history")
    public Map<String, Object> saveChatHistory(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> saved = chatHistoryService.saveChatHistory(user.getId(), body);
            result.put("success", true);
            result.putAll(saved);
        } catch (Exception e) {
            log.error("保存会话历史失败: userId={}", user.getId(), e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取单个会话的最新记录（发送消息前的当前会话同步，避免拉全量历史）
     * 参数: chatId=会话ID；返回 { success, messages, meta, version, exists }
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
            result.put("version", data.get("version"));
            result.put("exists", data.get("exists"));
        } catch (Exception e) {
            log.error("加载单会话历史失败: userId={}, chatId={}", user.getId(), chatId, e);
            result.put("success", false);
            result.put("message", "加载失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 跨会话搜索当前用户的会话标题（含用户重命名标题）与消息内容。
     * 参数: q=关键字；offset/limit=稳定分页（默认 0/20，limit 上限 50）；
     *       timeFrom/timeTo=会话更新时间范围（毫秒时间戳，可空）；
     *       folderId=文件夹归属筛选（可空）；modelName=模型名称筛选（可空，精确匹配）。
     * 返回 { success, data, hasMore }，消息命中项包含 messageIndex 供前端精确定位。
     */
    @GetMapping("/chat/history/search")
    public Map<String, Object> searchChatHistory(@RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int offset,
                                                  @RequestParam(defaultValue = "20") int limit,
                                                  @RequestParam(required = false) Long timeFrom,
                                                  @RequestParam(required = false) Long timeTo,
                                                  @RequestParam(required = false) String folderId,
                                                  @RequestParam(required = false) String modelName,
                                                  HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        if (q == null || q.trim().isEmpty()) {
            result.put("data", Collections.emptyList());
            result.put("hasMore", false);
            return result;
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        int safeOffset = Math.max(0, offset);
        Map<String, Object> searchResult = chatHistoryService.searchChatHistory(
                user.getId(), q.trim(), safeLimit, safeOffset, timeFrom, timeTo, folderId, modelName);
        result.put("data", searchResult.get("results"));
        result.put("hasMore", Boolean.TRUE.equals(searchResult.get("hasMore")));
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

    // ========== 回收站（软删除会话的恢复与彻底删除） ==========

    /**
     * 查询当前用户的回收站会话列表（软删除的会话，按删除时间倒序）
     */
    @GetMapping("/chat/trash")
    public Map<String, Object> listTrash(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", chatHistoryService.listTrash(user.getId()));
        return result;
    }

    /**
     * 从回收站恢复会话（显式操作；恢复后参与多端同步，其他端刷新可见）
     * 请求体: { "chatIds": ["..."] }
     */
    @PostMapping("/chat/trash/restore")
    public Map<String, Object> restoreTrash(@RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        List<String> chatIds = new ArrayList<>();
        if (body.get("chatIds") instanceof List<?> list) {
            for (Object id : list) if (id instanceof String s && !s.isEmpty()) chatIds.add(s);
        }
        if (chatIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择要恢复的会话");
            return result;
        }
        try {
            List<String> restored = chatHistoryService.restoreFromTrash(user.getId(), chatIds);
            result.put("success", true);
            result.put("restored", restored);
        } catch (Exception e) {
            log.error("回收站恢复失败: userId={}", user.getId(), e);
            result.put("success", false);
            result.put("message", "恢复失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 彻底删除回收站中的会话（不可恢复；chatIds 为空数组表示清空回收站）
     * 请求体: { "chatIds": [...] }
     */
    @PostMapping("/chat/trash/purge")
    public Map<String, Object> purgeTrash(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        List<String> chatIds = new ArrayList<>();
        if (body != null && body.get("chatIds") instanceof List<?> list) {
            for (Object id : list) if (id instanceof String s && !s.isEmpty()) chatIds.add(s);
        }
        try {
            int purged = chatHistoryService.purgeTrash(user.getId(), chatIds);
            result.put("success", true);
            result.put("purged", purged);
        } catch (Exception e) {
            log.error("回收站彻底删除失败: userId={}", user.getId(), e);
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

