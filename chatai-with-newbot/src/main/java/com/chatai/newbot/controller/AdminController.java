package com.chatai.newbot.controller;

import com.chatai.newbot.config.AdminSupport;
import com.chatai.newbot.model.*;
import com.chatai.newbot.service.ChatHistoryService;
import com.chatai.newbot.service.PasswordHasher;
import com.chatai.newbot.service.ApiKeyCrypto;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.WebSearchService;
import com.chatai.newbot.service.AuditLogService;
import com.chatai.newbot.service.BillingSettingsService;
import com.chatai.newbot.service.SvgAvatarService;
import com.chatai.newbot.service.UpstreamModelCatalogService;
import com.chatai.newbot.service.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 后台管理 - 厂商/用户/用量/设置/公告/审计/分享模块。
 * 模型管理模块已拆分至 {@link AdminModelController}（路径前缀同为 /api/admin）。
 * 管理员身份校验与审计日志记录统一走 {@link AdminSupport}。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final StorageManager storageService;
    private final ChatHistoryService chatHistoryService;
    private final WebSearchService webSearchService;
    private final AuditLogService auditLogService;
    private final AdminSupport admin;
    private final BillingSettingsService billingSettingsService;
    private final SvgAvatarService svgAvatarService;
    private final UpstreamModelCatalogService upstreamModelCatalogService;
    private final ObservabilityService observabilityService;

    /** 注入后台管理所需的存储、安全校验、上游模型目录与运行指标服务。 */
    public AdminController(StorageManager storageService,
                           ChatHistoryService chatHistoryService, WebSearchService webSearchService,
                           AuditLogService auditLogService, AdminSupport admin,
                           BillingSettingsService billingSettingsService,
                           SvgAvatarService svgAvatarService,
                           UpstreamModelCatalogService upstreamModelCatalogService,
                           ObservabilityService observabilityService) {
        this.storageService = storageService;
        this.chatHistoryService = chatHistoryService;
        this.webSearchService = webSearchService;
        this.auditLogService = auditLogService;
        this.admin = admin;
        this.billingSettingsService = billingSettingsService;
        this.svgAvatarService = svgAvatarService;
        this.upstreamModelCatalogService = upstreamModelCatalogService;
        this.observabilityService = observabilityService;
    }

    // ========== 厂商信息 =========
    @GetMapping("/providers")
    public Map<String, Object> listProviders(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        // 预置厂商（已应用显示名覆盖；图标直接来自 providers.json），附带原始 defaultName
        List<Provider> presetProviders = storageService.getAllProviders();
        List<Map<String, Object>> presetList = new ArrayList<>();
        for (Provider p : presetProviders) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            // 原始名称（用于前端展示"预设名称"列）
            Provider raw = storageService.getProvider(p.getId());
            m.put("defaultName", raw != null ? raw.getName() : p.getName());
            m.put("icon", p.getIcon());
            m.put("type", "preset");
            // 预设厂商的默认 API 地址/协议/思考参数类型：供前端"添加模型"选完厂商后自动填充 baseurl，
            // 以及提交时兜底 apiUrl/protocol（fillProviderInfo 不兜底这两个字段）
            m.put("defaultApiUrl", p.getDefaultApiUrl());
            m.put("protocol", p.getProtocol());
            m.put("thinkingParamType", p.getThinkingParamType());
            m.put("modelCount", p.getModels() == null ? 0 : p.getModels().size());
            // 预置模型列表：供前端"快速接入"勾选、"添加模型"预设下拉、计算"已全部接入"状态
            m.put("models", p.getModels() == null ? Collections.emptyList() : p.getModels());
            presetList.add(m);
        }
        // 自定义厂商（按 providerName 聚合，来源：ModelConfig）
        List<Map<String, Object>> customProviders = storageService.listCustomProviders();
        // 合并
        List<Object> merged = new ArrayList<>();
        merged.addAll(presetList);
        merged.addAll(customProviders);
        result.put("success", true);
        result.put("data", merged);
        result.put("presetCount", presetList.size());
        result.put("customCount", customProviders.size());
        return result;
    }

    /**
     * 修改厂商显示名/图标
     * - 预置厂商(providerId ∈ providers.json): 仅修改显示名/图标, ID/协议/默认URL等不可改
     * - 自定义厂商(providerId="__custom__"): 仅修改 providerName=oldName 的 ModelConfig
     * 请求体: { "name": "新的显示名", "icon": "🔮", "oldName": "原显示名(自定义厂商必填)" }
     *         icon 可省略/为空 表示不改图标
     */
    @PatchMapping("/providers/{providerId}")
    public Map<String, Object> renameProvider(@PathVariable String providerId,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String newName = (String) body.get("name");
        String newIcon = (String) body.get("icon");
        String oldName = (String) body.get("oldName");
        if (newName == null || newName.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "厂商名称不能为空");
            return result;
        }
        if (newName.trim().length() > 100) {
            result.put("success", false);
            result.put("message", "厂商名称过长（最多100字符）");
            return result;
        }
        if (newIcon != null && newIcon.length() > 4) {
            result.put("success", false);
            result.put("message", "图标过长（最多4字符）");
            return result;
        }
        // providerId 解码（自定义厂商的 id 可能包含中文）
        String decodedId;
        try {
            decodedId = java.net.URLDecoder.decode(providerId, "UTF-8");
        } catch (Exception e) {
            decodedId = providerId;
        }
        // 自定义厂商时 oldName 是必填的（用于精确定位要修改的模型）
        if (decodedId.startsWith("__custom__") && (oldName == null || oldName.trim().isEmpty())) {
            result.put("success", false);
            result.put("message", "自定义厂商必须提供原名称 oldName");
            return result;
        }
        try {
            int updated = storageService.renameProvider(decodedId, newName.trim(), newIcon, oldName);
            log.info("管理员修改厂商: providerId={}, oldName={}, newName={}, newIcon={}, 同步模型数={}",
                    decodedId, oldName, newName.trim(), newIcon, updated);
            admin.audit(request, "provider.rename", "修改厂商 " + (oldName != null && !oldName.trim().isEmpty() ? oldName + " → " : "") + newName.trim()
                    + "（同步 " + updated + " 个模型）");
            result.put("success", true);
            result.put("message", "已更新，影响 " + updated + " 个模型");
            result.put("updatedModels", updated);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ========== 用户管理 ==========

    @GetMapping("/users")
    public Map<String, Object> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        if (size > 500) size = 500;
        int total = storageService.countUsers(username);
        List<User> users = storageService.queryUsers(username, (page - 1) * size, size);
        // 不返回密码
        List<Map<String, Object>> userList = users.stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("role", u.getRole());
            m.put("createdAt", u.getCreatedAt());
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("lastLoginIp", u.getLastLoginIp());
            m.put("lastLoginBrowser", u.getLastLoginBrowser());
            m.put("displayName", u.getDisplayName());
            m.put("email", u.getEmail());
            m.put("phone", u.getPhone());
            m.put("department", u.getDepartment());
            m.put("jobTitle", u.getJobTitle());
            m.put("bio", u.getBio());
            m.put("avatarType", u.getAvatarType());
            m.put("avatarValue", u.getAvatarValue());
            m.put("allowedModelIds", u.getAllowedModelIds());
            m.put("disabled", u.isDisabled());
            m.put("dailyLimitType", u.getDailyLimitType());
            m.put("dailyLimitValue", u.getDailyLimitValue());
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("data", userList);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", Math.max(1, (int) Math.ceil((double) total / size)));
        return result;
    }

    @PostMapping("/users")
    public Map<String, Object> addUser(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String role = (String) body.get("role");
        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "用户名和密码不能为空");
            return result;
        }
        if (role == null || role.trim().isEmpty()) role = "user";
        User user = storageService.register(username.trim(), password, request.getRemoteAddr());
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户名已存在或不可用");
            return result;
        }
        // 如果指定角色为admin，更新角色
        if ("admin".equals(role) && !"admin".equals(user.getRole())) {
            user.setRole("admin");
            storageService.updateUser(user);
        }
        admin.audit(request, "user.add", "新增用户 " + user.getUsername() + "（角色 " + role + "）");
        result.put("success", true);
        result.put("message", "添加成功");
        return result;
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        User user = storageService.getUserById(id);
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }
        String role = (String) body.get("role");
        if (role != null && !user.getUsername().equals("admin")) {
            user.setRole(role);
        }
        boolean passwordReset = false;
        String password = (String) body.get("password");
        if (password != null && !password.trim().isEmpty()) {
            user.setPassword(PasswordHasher.hash(password));
            passwordReset = true;
        }
        // 禁用/启用：内置 admin 与当前登录账号不可禁用，防止把自己锁在门外
        Object disabledRaw = body.get("disabled");
        if (disabledRaw instanceof Boolean disabled) {
            User current = (User) request.getAttribute("currentUser");
            if (disabled && "admin".equals(user.getUsername())) {
                result.put("success", false);
                result.put("message", "内置管理员账号不可禁用");
                return result;
            }
            if (disabled && current != null && current.getId().equals(user.getId())) {
                result.put("success", false);
                result.put("message", "不能禁用自己的账号");
                return result;
            }
            user.setDisabled(disabled);
        }
        // 单用户每日限额：type 为 "count"（每日次数）或 "token"（每日Token量），二选一互斥；
        // 传空/null 或 value<=0 视为清除个人限额（回退全局配额）
        if (body.containsKey("dailyLimitType") || body.containsKey("dailyLimitValue")) {
            Object typeRaw = body.get("dailyLimitType");
            String limitType = typeRaw instanceof String s ? s.trim() : "";
            int limitValue = 0;
            Object valueRaw = body.get("dailyLimitValue");
            if (valueRaw instanceof Number n) {
                limitValue = n.intValue();
            } else if (valueRaw instanceof String vs && !vs.trim().isEmpty()) {
                try { limitValue = Integer.parseInt(vs.trim()); } catch (NumberFormatException ignore) { }
            }
            if (("count".equals(limitType) || "token".equals(limitType)) && limitValue > 0) {
                user.setDailyLimitType(limitType);
                user.setDailyLimitValue(limitValue);
            } else {
                user.setDailyLimitType(null);
                user.setDailyLimitValue(0);
            }
        }
        // 用户资料字段按需更新，未提交的字段保持不变。
        if (body.containsKey("displayName")) user.setDisplayName(adminProfileText(body, "displayName", 80));
        if (body.containsKey("email")) user.setEmail(adminProfileText(body, "email", 160));
        if (body.containsKey("phone")) user.setPhone(adminProfileText(body, "phone", 40));
        if (body.containsKey("department")) user.setDepartment(adminProfileText(body, "department", 100));
        if (body.containsKey("jobTitle")) user.setJobTitle(adminProfileText(body, "jobTitle", 100));
        if (body.containsKey("bio")) user.setBio(adminProfileText(body, "bio", 500));
        if (body.containsKey("avatarType") || body.containsKey("avatarValue")) {
            String avatarType = adminProfileText(body, "avatarType", 20);
            if ("svg".equals(avatarType)) {
                user.setAvatarType("svg");
                user.setAvatarValue(svgAvatarService.sanitize(adminProfileText(body, "avatarValue", 20_000)));
            } else {
                user.setAvatarType("default");
                user.setAvatarValue("");
            }
        }
        storageService.updateUser(user);
        admin.audit(request, "user.update", "更新用户 " + user.getUsername()
                + (passwordReset ? "（重置密码）" : "") + (user.isDisabled() ? "（禁用）" : ""));
        // 重置密码或禁用后强制下线，旧登录态立即失效
        if (passwordReset || user.isDisabled()) {
            storageService.removeTokensByUserId(user.getId());
        }
        result.put("success", true);
        result.put("message", "保存成功");
        return result;
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable String id,
                                           HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        boolean deleted = storageService.deleteUser(id);
        if (deleted) {
            admin.audit(request, "user.delete", "删除用户 " + id);
        }
        result.put("success", deleted);
        if (!deleted) result.put("message", "用户不存在或不可删除");
        return result;
    }

    /**
     * 批量删除用户。请求体: {"ids": ["..."]}（管理员账号由存储层保护，不会被删除）
     */
    @PostMapping("/users/batch-delete")
    public Map<String, Object> batchDeleteUsers(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Object idsObj = body == null ? null : body.get("ids");
        if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要删除的用户");
            return result;
        }
        int deleted = 0;
        for (Object idObj : ids) {
            if (idObj instanceof String id && !id.isEmpty() && storageService.deleteUser(id)) {
                deleted++;
            }
        }
        log.info("后台批量删除用户：请求 {} 条，实际删除 {} 条", ids.size(), deleted);
        admin.audit(request, "user.batchDelete", "批量删除用户 " + deleted + " 个");
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    @PutMapping("/users/{id}/permissions")
    public Map<String, Object> updateUserPermissions(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        User user = storageService.getUserById(id);
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }

        @SuppressWarnings("unchecked")
        List<String> modelIds = (List<String>) body.get("allowedModelIds");
        if (modelIds != null) {
            user.setAllowedModelIds(modelIds);
            storageService.updateUser(user);
            admin.audit(request, "user.permissions", "更新用户 " + user.getUsername() + " 可用模型（" + modelIds.size() + " 个）");
        }

        result.put("success", true);
        return result;
    }

    // ========== 使用记录 ==========

    /**
     * 使用记录查询 - 支持分页+筛选
     * 参数: page(从1开始), size, username, modelName, date
     */
    @GetMapping("/usage")
    public Map<String, Object> getUsageLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        // 校验日期范围（最多 30 天）
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        // 筛选/分页下推到存储层（SQLite 模式走 SQL，避免全表拉取到内存）
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        int total = storageService.countUsageLogs(username, modelName, startDate, endDate);
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        int offset = Math.min((page - 1) * size, total);
        List<UsageLog> pageData = storageService.queryUsageLogs(username, modelName, startDate, endDate, offset, size);

        result.put("success", true);
        result.put("data", pageData);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        return result;
    }

    /**
     * 筛选选项 - 返回可用的用户名和模型名列表（用于下拉框）
     */
    @GetMapping("/usage/filters")
    public Map<String, Object> getUsageFilters(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        List<String> usernames = storageService.getUsageUsernames();
        List<String> modelNames = storageService.getUsageModelNames(null);

        result.put("success", true);
        result.put("usernames", usernames);
        result.put("modelNames", modelNames);
        return result;
    }

    /**
     * 使用统计 - 按用户+日期+模型维度聚合，支持搜索+分页
     * getAll=true 时不分页，返回全部聚合数据（图表专用）
     */
    @GetMapping("/usage/stats")
    public Map<String, Object> getUsageStats(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean getAll,
            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        // 校验日期范围（最多 30 天）
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        // 筛选+聚合+分页均下推到存储层 SQL 完成
        List<String> nameList = (username != null && !username.isEmpty())
                ? Collections.singletonList(username) : null;

        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        result.put("success", true);

        if (getAll) {
            // 图表专用：不分页，一次性返回全量聚合数据
            List<Map<String, Object>> statsList = storageService.aggregateUsageStats(nameList, modelName, startDate, endDate);
            result.put("total", statsList.size());
            result.put("data", statsList);
            result.put("page", 1);
            result.put("size", statsList.size());
            result.put("totalPages", 1);
        } else {
            // 列表专用：SQL 分页
            int total = storageService.countUsageStatGroups(nameList, modelName, startDate, endDate);
            List<Map<String, Object>> pageData = storageService.aggregateUsageStats(
                    nameList, modelName, startDate, endDate, (page - 1) * size, size);
            result.put("total", total);
            result.put("data", pageData);
            result.put("page", page);
            result.put("size", size);
            result.put("totalPages", Math.max(1, (int) Math.ceil((double) total / size)));
        }
        return result;
    }

    // ========== 系统设置（存储信息） ==========

    /**
     * 获取存储信息（当前架构为 SQLite 单通道，JSON 存储通道已移除，故 useSqlite 恒为 true）
     * 返回: { "success": true, "data": { "useSqlite": true, "dbFileSize": "2.3MB" } }
     */
    @GetMapping("/settings/storage")
    public Map<String, Object> getStorageSettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("useSqlite", true);
        data.put("dbFileSize", storageService.getDbFileSize());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    // ========== 系统设置（每日配额） ==========

    /**
     * 获取每日调用配额设置
     * 返回: { "success": true, "data": { "dailyChatLimit": 100 } }（0 表示不限制）
     */
    @GetMapping("/settings/quota")
    public Map<String, Object> getQuotaSettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dailyChatLimit", storageService.getDailyChatLimit());
        data.put("dailyTokenLimit", storageService.getDailyTokenLimit());
        data.put("dailyCostLimitCny", storageService.getDailyCostLimitCny());
        data.put("rateLimitPerMinute", storageService.getRateLimitPerMinute());
        data.put("contextMaxMessages", storageService.getContextMaxMessages());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 设置每日调用配额（对非 admin 用户生效，0 表示不限制）
     * 请求体: { "dailyChatLimit": 100 }
     */
    @PutMapping("/settings/quota")
    public Map<String, Object> setQuotaSettings(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Object raw = body == null ? null : body.get("dailyChatLimit");
        if (!(raw instanceof Number)) {
            result.put("success", false);
            result.put("message", "请指定 dailyChatLimit 参数（整数，0 表示不限制）");
            return result;
        }
        int limit = ((Number) raw).intValue();
        if (limit < 0 || limit > 100000) {
            result.put("success", false);
            result.put("message", "配额范围应为 0 ~ 100000（0 表示不限制）");
            return result;
        }
        // 每分钟限流（可选，0 表示不限制）
        int ratePerMinute = -1;
        if (body != null && body.get("rateLimitPerMinute") instanceof Number rn) {
            ratePerMinute = rn.intValue();
            if (ratePerMinute < 0 || ratePerMinute > 10000) {
                result.put("success", false);
                result.put("message", "每分钟限流范围应为 0 ~ 10000（0 表示不限制）");
                return result;
            }
        }
        // 上下文最大消息条数（可选，0 表示不限制）
        int contextMax = -1;
        if (body != null && body.get("contextMaxMessages") instanceof Number cn) {
            contextMax = cn.intValue();
            if (contextMax < 0 || contextMax > 1000) {
                result.put("success", false);
                result.put("message", "上下文最大条数范围应为 0 ~ 1000（0 表示不限制）");
                return result;
            }
        }
        long tokenLimit = -1L;
        if (body != null && body.get("dailyTokenLimit") instanceof Number tn) {
            tokenLimit = tn.longValue();
            if (tokenLimit < 0 || tokenLimit > 10_000_000_000L) {
                result.put("success", false);
                result.put("message", "每日 Token 配额范围应为 0 ~ 100亿");
                return result;
            }
        }
        double costLimitCny = -1D;
        if (body != null && body.get("dailyCostLimitCny") instanceof Number cost) {
            costLimitCny = cost.doubleValue();
            if (!Double.isFinite(costLimitCny) || costLimitCny < 0 || costLimitCny > 100_000_000D) {
                result.put("success", false);
                result.put("message", "每日金额配额范围应为 0 ~ 1亿元人民币");
                return result;
            }
        }
        try {
            storageService.setDailyChatLimit(limit);
            if (ratePerMinute >= 0) storageService.setRateLimitPerMinute(ratePerMinute);
            if (contextMax >= 0) storageService.setContextMaxMessages(contextMax);
            if (tokenLimit >= 0) storageService.setDailyTokenLimit(tokenLimit);
            if (costLimitCny >= 0) storageService.setDailyCostLimitCny(costLimitCny);
            admin.audit(request, "settings.quota", "修改配额设置：每日上限 " + limit);
            result.put("success", true);
            List<String> messages = new ArrayList<>();
            messages.add(limit == 0 ? "已取消每日调用限制" : "每日调用上限已设为 " + limit + " 次");
            if (tokenLimit >= 0) messages.add(tokenLimit == 0 ? "已取消每日 Token 限制" : "每日 Token 上限已设为 " + tokenLimit);
            if (costLimitCny >= 0) messages.add(costLimitCny == 0 ? "已取消每日金额限制" : "每日金额上限已设为 ¥" + costLimitCny);
            if (ratePerMinute >= 0) messages.add(ratePerMinute == 0 ? "已取消每分钟限流" : "每分钟上限已设为 " + ratePerMinute + " 次");
            if (contextMax >= 0) messages.add(contextMax == 0 ? "已取消上下文条数限制" : "上下文上限已设为 " + contextMax + " 条");
            result.put("messages", messages);
            result.put("message", String.join("；", messages));
        } catch (Exception e) {
            log.error("保存配额设置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    // ========== 系统设置（成本与币种） ==========

    /** 获取 Token/金额默认展示方式、默认币种及人民币汇率列表。 */
    @GetMapping("/settings/billing")
    public Map<String, Object> getBillingSettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", billingSettingsService.getConfig());
        return result;
    }

    /** 保存 Token/金额默认展示方式、默认币种及人民币汇率列表。 */
    @PutMapping("/settings/billing")
    public Map<String, Object> setBillingSettings(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("data", billingSettingsService.saveConfig(body));
            result.put("success", true);
            result.put("message", "计费与币种设置已保存");
            admin.audit(request, "计费设置", "修改计费展示与币种汇率配置");
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("保存计费设置失败", e);
            result.put("success", false);
            result.put("message", "保存计费设置失败");
        }
        return result;
    }

    // ========== 系统设置（联网搜索 Tavily） ==========

    /**
     * 获取联网搜索设置（API Key 仅返回掩码）
     * 返回: { "success": true, "data": { "enabled": true, "apiKeyMasked": "tvly-****Cn5", "hasKey": true } }
     */
    @GetMapping("/settings/websearch")
    public Map<String, Object> getWebSearchSettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String apiKey = storageService.getTavilyApiKey();
        boolean hasKey = apiKey != null && !apiKey.trim().isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", storageService.getWebSearchEnabled());
        data.put("hasKey", hasKey);
        data.put("apiKeyMasked", hasKey ? maskKey(apiKey.trim()) : "");
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 保存联网搜索设置
     * 请求体: { "enabled": true, "apiKey": "tvly-xxx" }（apiKey 为空或含 * 时保留原 Key）
     */
    @PutMapping("/settings/websearch")
    public Map<String, Object> setWebSearchSettings(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        try {
            // API Key：为空或掩码值（含*）时保留原 Key，与模型管理的 Key 更新策略一致
            Object rawKey = body == null ? null : body.get("apiKey");
            if (rawKey instanceof String s && !s.trim().isEmpty() && !s.contains("*")) {
                storageService.setTavilyApiKey(s.trim());
            }
            Object rawEnabled = body == null ? null : body.get("enabled");
            if (rawEnabled instanceof Boolean b) {
                // 开启前校验已配置 Key，避免前台开关开了但实际不可用
                String key = storageService.getTavilyApiKey();
                if (b && (key == null || key.trim().isEmpty())) {
                    result.put("success", false);
                    result.put("message", "请先配置 Tavily API Key 再开启联网搜索");
                    return result;
                }
                storageService.setWebSearchEnabled(b);
            }
            admin.audit(request, "settings.websearch", "保存联网搜索设置");
            result.put("success", true);
            result.put("message", "联网搜索设置已保存");
        } catch (Exception e) {
            log.error("保存联网搜索设置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * Tavily 连通性测试：用请求体中的 Key（或已保存的 Key）发一次最小检索
     * 请求体: { "apiKey": "tvly-xxx" }（可空/掩码，空时用已保存的 Key）
     */
    @PostMapping("/settings/websearch/test")
    public Map<String, Object> testWebSearch(@RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String apiKey = null;
        Object rawKey = body == null ? null : body.get("apiKey");
        if (rawKey instanceof String s && !s.trim().isEmpty() && !s.contains("*")) {
            apiKey = s.trim();
        } else {
            apiKey = storageService.getTavilyApiKey();
        }
        Map<String, Object> res = webSearchService.testConnection(apiKey);
        admin.audit(request, "settings.websearch.test", "测试联网搜索连通性");
        return res;
    }

    /** API Key 掩码：保留前 5 后 3 位，中间用 * 代替 */
    private String maskKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 5) + "****" + key.substring(key.length() - 3);
    }

    // ========== 系统公告管理 ==========

    /**
     * 获取全部公告（含历史公告），附带状态判定
     * status: active=生效中 scheduled=待生效 expired=已过期 offline=已下线
     */
    @GetMapping("/announcements")
    public Map<String, Object> listAnnouncements(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Announcement a : storageService.getAllAnnouncements()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("title", a.getTitle() == null ? "" : a.getTitle());
            item.put("content", a.getContent());
            item.put("startAt", a.getStartAt() == null ? "" : a.getStartAt());
            item.put("endAt", a.getEndAt() == null ? "" : a.getEndAt());
            item.put("enabled", a.isEnabled());
            item.put("createdAt", a.getCreatedAt());
            item.put("updatedAt", a.getUpdatedAt());
            item.put("status", resolveAnnouncementStatus(a));
            list.add(item);
        }
        result.put("success", true);
        result.put("data", list);
        return result;
    }

    /**
     * 发布新公告（自动下线其它公告）
     * 请求体: { "title": "公告标题", "content": "公告正文", "startAt": "yyyy-MM-dd HH:mm:ss"可空, "endAt": "yyyy-MM-dd HH:mm:ss"可空 }
     */
    @PostMapping("/announcements")
    public Map<String, Object> publishAnnouncement(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String content = body == null ? "" : (body.get("content") instanceof String s ? s.trim() : "");
        if (content.isEmpty()) {
            result.put("success", false);
            result.put("message", "公告内容不能为空");
            return result;
        }
        if (content.length() > 5000) {
            result.put("success", false);
            result.put("message", "公告内容不能超过 5000 字符");
            return result;
        }
        String title = body.get("title") instanceof String s ? s.trim() : "";
        if (title.isEmpty()) {
            result.put("success", false);
            result.put("message", "公告标题不能为空");
            return result;
        }
        if (title.length() > 100) {
            result.put("success", false);
            result.put("message", "公告标题不能超过 100 字符");
            return result;
        }
        String startAt = body.get("startAt") instanceof String s ? s.trim() : "";
        String endAt = body.get("endAt") instanceof String s ? s.trim() : "";
        String periodError = validateAnnouncementPeriod(startAt, endAt);
        if (periodError != null) {
            result.put("success", false);
            result.put("message", periodError);
            return result;
        }
        try {
            storageService.publishAnnouncement(title, content, startAt, endAt);
            admin.audit(request, "announcement.publish", "发布公告：" + title);
            result.put("success", true);
            result.put("message", "公告已发布");
        } catch (Exception e) {
            log.error("发布公告失败", e);
            result.put("success", false);
            result.put("message", "发布失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 重新生效/更改公告期：更新公告期（可选同时更新标题与内容）并启用，其它公告自动下线
     * 请求体: { "title": "可空（空=不修改）", "content": "可空（空=不修改）", "startAt": "可空", "endAt": "可空" }
     */
    @PutMapping("/announcements/{id}")
    public Map<String, Object> republishAnnouncement(@PathVariable String id,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        String content = body != null && body.get("content") instanceof String s ? s.trim() : "";
        if (content.length() > 5000) {
            result.put("success", false);
            result.put("message", "公告内容不能超过 5000 字符");
            return result;
        }
        String title = body != null && body.get("title") instanceof String s ? s.trim() : "";
        if (title.length() > 100) {
            result.put("success", false);
            result.put("message", "公告标题不能超过 100 字符");
            return result;
        }
        String startAt = body != null && body.get("startAt") instanceof String s ? s.trim() : "";
        String endAt = body != null && body.get("endAt") instanceof String s ? s.trim() : "";
        String periodError = validateAnnouncementPeriod(startAt, endAt);
        if (periodError != null) {
            result.put("success", false);
            result.put("message", periodError);
            return result;
        }
        try {
            Announcement a = storageService.republishAnnouncement(id, title, content, startAt, endAt);
            if (a == null) {
                result.put("success", false);
                result.put("message", "公告不存在");
                return result;
            }
            admin.audit(request, "announcement.update", "更新/重新生效公告 " + id);
            result.put("success", true);
            result.put("message", "公告已重新生效");
        } catch (Exception e) {
            log.error("更新公告失败: id={}", id, e);
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 下线公告（保留历史记录，可重新生效）
     */
    @PutMapping("/announcements/{id}/offline")
    public Map<String, Object> offlineAnnouncement(@PathVariable String id,
                                                   HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (storageService.offlineAnnouncement(id)) {
            admin.audit(request, "announcement.offline", "下线公告 " + id);
            result.put("success", true);
            result.put("message", "公告已下线");
        } else {
            result.put("success", false);
            result.put("message", "公告不存在");
        }
        return result;
    }

    /**
     * 删除公告记录
     */
    @DeleteMapping("/announcements/{id}")
    public Map<String, Object> deleteAnnouncement(@PathVariable String id,
                                                  HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (storageService.deleteAnnouncement(id)) {
            admin.audit(request, "announcement.delete", "删除公告 " + id);
            result.put("success", true);
            result.put("message", "公告已删除");
        } else {
            result.put("success", false);
            result.put("message", "公告不存在");
        }
        return result;
    }

    /**
     * 判定公告状态：已下线 > 待生效 > 已过期 > 生效中（时间解析失败视为未设置该边界）
     */
    private String resolveAnnouncementStatus(Announcement a) {
        if (!a.isEnabled()) return "offline";
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        if (a.getStartAt() != null && !a.getStartAt().isEmpty()) {
            try {
                if (now.isBefore(LocalDateTime.parse(a.getStartAt(), fmt))) return "scheduled";
            } catch (Exception ignore) {
                // 解析失败视为立即生效
            }
        }
        if (a.getEndAt() != null && !a.getEndAt().isEmpty()) {
            try {
                if (now.isAfter(LocalDateTime.parse(a.getEndAt(), fmt))) return "expired";
            } catch (Exception ignore) {
                // 解析失败视为长期有效
            }
        }
        return "active";
    }

    /**
     * 校验公告期参数：非空时必须为 yyyy-MM-dd HH:mm:ss，且开始时间早于结束时间
     * @return 错误信息，合法返回 null
     */
    private String validateAnnouncementPeriod(String startAt, String endAt) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime start = null;
        LocalDateTime end = null;
        if (startAt != null && !startAt.isEmpty()) {
            try {
                start = LocalDateTime.parse(startAt, fmt);
            } catch (Exception e) {
                return "公告期开始时间格式错误（yyyy-MM-dd HH:mm:ss）";
            }
        }
        if (endAt != null && !endAt.isEmpty()) {
            try {
                end = LocalDateTime.parse(endAt, fmt);
            } catch (Exception e) {
                return "公告期结束时间格式错误（yyyy-MM-dd HH:mm:ss）";
            }
        }
        if (start != null && end != null && !start.isBefore(end)) {
            return "公告期开始时间必须早于结束时间";
        }
        return null;
    }

    // ========== 系统设置（安全） ==========

    /**
     * 获取安全设置
     * 返回: { "success": true, "data": { "ipBindingEnabled": true } }
     */
    @GetMapping("/settings/security")
    public Map<String, Object> getSecuritySettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ipBindingEnabled", storageService.getIpBindingEnabled());
        data.put("registrationEnabled", storageService.getRegistrationEnabled());
        data.put("inviteCodeConfigured", storageService.hasRegistrationInviteCode());
        data.put("registrationCaptchaEnabled", storageService.getRegistrationCaptchaEnabled());
        data.put("botAvatarSvg", storageService.getBotAvatarSvg());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 设置安全选项（IP绑定校验：登录后IP变更强制下线）
     * 请求体: { "ipBindingEnabled": true }
     */
    @PutMapping("/settings/security")
    public Map<String, Object> setSecuritySettings(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (body == null || body.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要保存的安全设置");
            return result;
        }
        if (body.get("ipBindingEnabled") instanceof Boolean enabled) {
            storageService.setIpBindingEnabled(enabled);
        }
        if (body.get("registrationEnabled") instanceof Boolean enabled) {
            storageService.setRegistrationEnabled(enabled);
        }
        if (body.get("registrationCaptchaEnabled") instanceof Boolean enabled) {
            storageService.setRegistrationCaptchaEnabled(enabled);
        }
        if (body.containsKey("inviteCode")) {
            storageService.setRegistrationInviteCode(String.valueOf(body.get("inviteCode") == null ? "" : body.get("inviteCode")));
        }
        if (Boolean.TRUE.equals(body.get("clearInviteCode"))) {
            storageService.setRegistrationInviteCode("");
        }
        if (body.containsKey("botAvatarSvg")) {
            String svg = String.valueOf(body.get("botAvatarSvg") == null ? "" : body.get("botAvatarSvg"));
            storageService.setBotAvatarSvg(svgAvatarService.sanitize(svg));
        }
        admin.audit(request, "settings.security", "保存安全、注册与 Bot 头像设置");
        result.put("success", true);
        result.put("message", "保存成功");
        return result;
    }

    /**
     * 从厂商上游读取最新模型目录。API Key 优先使用请求体，其次复用该厂商任一已接入模型的 Key。
     */
    @PostMapping("/providers/{providerId}/fetch-models")
    public Map<String, Object> fetchProviderModels(@PathVariable String providerId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        admin.requireAdmin(request);
        Provider provider = storageService.getResolvedProvider(providerId);
        if (provider == null) return Map.of("success", false, "message", "厂商不存在");
        String apiUrl = body != null && body.get("apiUrl") != null
                ? String.valueOf(body.get("apiUrl")).trim() : provider.getDefaultApiUrl();
        String apiKey = body != null && body.get("apiKey") != null
                ? String.valueOf(body.get("apiKey")).trim() : "";
        if (apiKey.isBlank()) {
            apiKey = storageService.getAllModelConfigs().stream()
                    .filter(model -> providerId.equals(model.getProviderId()))
                    .map(ModelConfig::getApiKey)
                    .filter(key -> key != null && !key.isBlank())
                    .findFirst().orElse("");
        }
        try {
            List<ProviderModel> models = upstreamModelCatalogService.fetch(apiUrl, apiKey);
            admin.audit(request, "获取厂商模型目录", "获取厂商 " + provider.getName()
                    + " 上游模型目录（" + models.size() + " 个）");
            return Map.of("success", true, "data", models,
                    "endpoint", upstreamModelCatalogService.normalizeModelsEndpoint(apiUrl));
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 保存管理员确认后的厂商模型目录，快速接入与模型管理会立即读取到更新结果。
     */
    @PutMapping("/providers/{providerId}/models")
    public Map<String, Object> saveProviderModels(@PathVariable String providerId,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        admin.requireAdmin(request);
        Provider provider = storageService.getResolvedProvider(providerId);
        if (provider == null) return Map.of("success", false, "message", "厂商不存在");
        List<ProviderModel> models = new ArrayList<>();
        if (body != null && body.get("models") instanceof List<?> rawModels) {
            for (Object raw : rawModels) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                String id = map.get("id") == null ? "" : String.valueOf(map.get("id")).trim();
                if (id.isEmpty()) continue;
                ProviderModel model = new ProviderModel();
                model.setId(id);
                model.setName(map.get("name") == null ? id : String.valueOf(map.get("name")));
                model.setSupportsThinking(Boolean.TRUE.equals(map.get("supportsThinking")));
                model.setSupportsMultimodal(Boolean.TRUE.equals(map.get("supportsMultimodal")));
                models.add(model);
            }
        }
        storageService.saveProviderModels(providerId, models);
        admin.audit(request, "保存厂商模型目录", "保存厂商 " + provider.getName()
                + " 模型目录（" + models.size() + " 个）");
        return Map.of("success", true, "message", "已保存 " + models.size() + " 个模型", "data", models);
    }

    /**
     * 读取后台用户资料字段并限制长度。
     */
    private String adminProfileText(Map<String, Object> body, String key, int maxLength) {
        String value = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(key + " 字段过长");
        return value;
    }

    // ========== 审计日志 ==========

    /** 获取进程级请求、聊天流、延迟与内存指标快照。 */
    @GetMapping("/observability")
    public Map<String, Object> getObservability(HttpServletRequest request) {
        admin.requireAdmin(request);
        return Map.of("success", true, "healthy", storageService.isReady(),
                "data", observabilityService.snapshot());
    }

    /**
     * 分页查询审计日志（时间倒序）
     * 参数: page(从1开始), size, username(模糊), action(精确), startDate, endDate
     */
    @GetMapping("/audit-logs")
    public Map<String, Object> listAuditLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest request) {
        admin.requireAdmin(request);
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        Map<String, Object> result = new HashMap<>(auditLogService.query(username, action, startDate, endDate, page, size));
        result.put("success", true);
        return result;
    }

    /**
     * 已出现过的操作类型列表（前端筛选下拉用）
     */
    @GetMapping("/audit-logs/actions")
    public Map<String, Object> listAuditActions(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", auditLogService.listActions());
        return result;
    }

    /**
     * 重置（清空）全部审计日志：需二次确认并校验当前管理员密码。
     * 清空后立即补记一条重置审计，因此重置后日志中仅保留这一条记录。
     * 请求体: { "password": "管理员当前登录密码" }
     */
    @PostMapping("/audit-logs/reset")
    public Map<String, Object> resetAuditLogs(@RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        String password = body == null ? null : body.get("password");
        if (password == null || password.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请输入管理员密码");
            return result;
        }
        // 校验当前登录管理员的密码，验证通过才允许清空
        if (current == null || storageService.authenticate(current.getUsername(), password) == null) {
            result.put("success", false);
            result.put("message", "管理员密码错误");
            return result;
        }
        int deleted = auditLogService.deleteAll();
        // 清空后补记一条重置记录，确保重置后日志中仅此一条
        admin.audit(request, "audit.reset", "重置审计日志（清空历史 " + deleted + " 条）");
        log.info("管理员 {} 重置审计日志，清空 {} 条", current.getUsername(), deleted);
        result.put("success", true);
        result.put("message", "审计日志已重置");
        result.put("deleted", deleted);
        return result;
    }

    // ========== 分享管理 ==========

    /**
     * 构建全量分享列表（轻量列 + 失效状态判定，不加载消息正文）
     * status: valid=有效 expired=已过期 exhausted=访问次数已用完 orphaned=源会话已被删除
     */
    private List<Map<String, Object>> buildShareList() {
        List<ChatShare> shares = storageService.getAllChatShares();
        // 按用户分组做一次性会话存在性检查（不加载消息正文）
        Map<String, List<String>> chatIdsByUser = new HashMap<>();
        for (ChatShare s : shares) {
            chatIdsByUser.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s.getChatId());
        }
        Map<String, Set<String>> existingByUser = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : chatIdsByUser.entrySet()) {
            existingByUser.put(entry.getKey(),
                    chatHistoryService.filterExistingChats(entry.getKey(), entry.getValue()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChatShare s : shares) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("chatId", s.getChatId());
            item.put("userId", s.getUserId());
            item.put("userName", s.getUserName());
            item.put("title", s.getTitle());
            item.put("createdAt", s.getCreatedAt());
            item.put("expiresAt", s.getExpiresAt());
            item.put("accessCount", s.getAccessCount());
            item.put("maxViews", s.getMaxViews());
            item.put("passwordProtected", s.getPasswordHash() != null && !s.getPasswordHash().isBlank());
            item.put("password", ShareController.sharePasswordPlain(s));
            item.put("status", resolveShareStatus(s,
                    existingByUser.getOrDefault(s.getUserId(), Collections.emptySet())));
            list.add(item);
        }
        return list;
    }

    /**
     * 分页查询全部用户的分享记录（服务端筛选+分页）
     * 参数：username 分享者模糊匹配；status 状态筛选（valid/expired/exhausted/orphaned）
     * 额外返回 invalidCount（全量失效条数，与筛选条件无关，供「清除失效」按钮用）
     */
    @GetMapping("/shares")
    public Map<String, Object> listShares(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        if (size > 500) size = 500;
        List<Map<String, Object>> all = buildShareList();
        long invalidCount = all.stream().filter(i -> !"valid".equals(i.get("status"))).count();
        // 服务端筛选：分享者模糊匹配 + 状态
        List<Map<String, Object>> filtered = all;
        if (username != null && !username.isEmpty()) {
            String kw = username.toLowerCase();
            filtered = filtered.stream()
                    .filter(i -> i.get("userName") != null && ((String) i.get("userName")).toLowerCase().contains(kw))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isEmpty()) {
            String st = status;
            filtered = filtered.stream()
                    .filter(i -> st.equals(i.get("status")))
                    .collect(Collectors.toList());
        }
        int total = filtered.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        result.put("success", true);
        result.put("data", filtered.subList(fromIndex, toIndex));
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", Math.max(1, (int) Math.ceil((double) total / size)));
        result.put("invalidCount", invalidCount);
        return result;
    }

    /**
     * 一键清除全部失效分享（已过期 + 源会话已删），失效判定在服务端完成
     */
    @PostMapping("/shares/delete-invalid")
    public Map<String, Object> deleteInvalidShares(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        int deleted = 0;
        for (Map<String, Object> item : buildShareList()) {
            if (!"valid".equals(item.get("status"))
                    && storageService.deleteChatShare((String) item.get("id"))) {
                deleted++;
            }
        }
        log.info("后台清除失效分享：实际删除 {} 条", deleted);
        admin.audit(request, "share.batchDelete", "清除失效分享 " + deleted + " 条");
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * 批量删除分享记录（批量清除失效分享/批量撤销）。请求体: {"ids": ["..."]}
     */
    @PostMapping("/shares/batch-delete")
    public Map<String, Object> batchDeleteShares(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Object idsObj = body == null ? null : body.get("ids");
        if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要删除的分享");
            return result;
        }
        int deleted = 0;
        for (Object idObj : ids) {
            if (idObj instanceof String id && !id.isEmpty() && storageService.deleteChatShare(id)) {
                deleted++;
            }
        }
        log.info("后台批量删除分享：请求 {} 条，实际删除 {} 条", ids.size(), deleted);
        admin.audit(request, "share.batchDelete", "批量删除分享 " + deleted + " 条");
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * 管理员修改任意分享的访问密码与次数上限。
     * 请求体：password=新密码（空串=关闭密码）、maxViews=新上限（0=不限，缺省=保持原值）、resetAccessCount=是否清零已访问次数
     */
    @PutMapping("/shares/{id}/settings")
    public Map<String, Object> updateShareSettings(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();

        ChatShare share = storageService.getChatShareById(id);
        if (share == null) {
            result.put("success", false);
            result.put("message", "分享不存在");
            return result;
        }

        String password = body != null && body.get("password") != null
                ? String.valueOf(body.get("password")).trim() : "";
        if (password.length() > 100) {
            result.put("success", false);
            result.put("message", "分享密码不能超过 100 个字符");
            return result;
        }
        int maxViews = share.getMaxViews();
        if (body != null && body.get("maxViews") != null) {
            try {
                maxViews = Math.max(0, Math.min(1_000_000, Integer.parseInt(String.valueOf(body.get("maxViews")))));
            } catch (NumberFormatException e) {
                result.put("success", false);
                result.put("message", "访问次数上限必须是非负整数");
                return result;
            }
        }
        boolean resetAccessCount = body != null && Boolean.TRUE.equals(body.get("resetAccessCount"));

        boolean ok = storageService.updateChatShareSecurity(id,
                password.isEmpty() ? null : PasswordHasher.hash(password),
                password.isEmpty() ? null : ApiKeyCrypto.encrypt(password),
                maxViews, resetAccessCount);
        if (!ok) {
            result.put("success", false);
            result.put("message", "修改失败，请稍后重试");
            return result;
        }
        admin.audit(request, "修改分享安全设置", "修改分享 " + id + "（" + share.getUserName() + "）安全设置："
                + (password.isEmpty() ? "关闭密码" : "设置密码") + "，上限 " + (maxViews > 0 ? maxViews : "不限")
                + (resetAccessCount ? "，计数已清零" : ""));

        ChatShare updated = storageService.getChatShareById(id);
        result.put("success", true);
        result.put("data", Map.of(
                "passwordProtected", updated.getPasswordHash() != null && !updated.getPasswordHash().isBlank(),
                "password", ShareController.sharePasswordPlain(updated),
                "accessCount", updated.getAccessCount(),
                "maxViews", updated.getMaxViews()));
        return result;
    }

    /**
     * 判定分享状态：已过期 > 次数已用完 > 源会话已删 > 有效（过期口径与 ShareController.view 一致）
     * 注意：maxViews<=0 表示不限次数；管理端列表未做快照判断，快照分享源会话删除后仍标 orphaned
     * 仅影响展示，实际访问以快照为准
     * @param existingChatIds 该用户名下仍存在的会话ID集合
     */
    private String resolveShareStatus(ChatShare s, Set<String> existingChatIds) {
        if (s.getExpiresAt() != null && !s.getExpiresAt().isEmpty()) {
            try {
                LocalDateTime expiry = LocalDateTime.parse(s.getExpiresAt(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                if (LocalDateTime.now().isAfter(expiry)) return "expired";
            } catch (Exception ignore) {
                // 时间解析失败视为未设置过期
            }
        }
        if (s.getMaxViews() > 0 && s.getAccessCount() >= s.getMaxViews()) return "exhausted";
        return existingChatIds.contains(s.getChatId()) ? "valid" : "orphaned";
    }

    // ========== 工具方法 ==========

    /**
     * 校验统计接口的日期范围：两个日期不能同时为空（只填一边视为单日查询，最多 30 天）
     * 返回 null 表示通过；返回非 null Map（success=false, message=...）表示失败
     */
    private Map<String, Object> validateDateRange(String startDate, String endDate) {
        boolean hasStart = startDate != null && !startDate.isEmpty();
        boolean hasEnd = endDate != null && !endDate.isEmpty();
        if (!hasStart && !hasEnd) return null; // 都为空 = 不筛选
        if (hasStart && !startDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "开始日期格式错误，应为 yyyy-MM-dd");
            return err;
        }
        if (hasEnd && !endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "结束日期格式错误，应为 yyyy-MM-dd");
            return err;
        }
        // 只填一个就当作单日：复制到另一边
        String s = hasStart ? startDate : endDate;
        String e = hasEnd ? endDate : startDate;
        try {
            LocalDate start = LocalDate.parse(s);
            LocalDate end = LocalDate.parse(e);
            if (end.isBefore(start)) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "结束日期不能早于开始日期");
                return err;
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > 30) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "统计时间范围不能超过 30 天（当前 " + days + " 天）");
                return err;
            }
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "日期解析失败，请使用 yyyy-MM-dd 格式");
            return err;
        }
        return null;
    }
}
