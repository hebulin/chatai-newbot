package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.JsonFileStorageService;
import com.chatai.newbot.service.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final StorageManager storageService;

    public AdminController(StorageManager storageService) {
        this.storageService = storageService;
    }

    private boolean checkAdmin(HttpServletRequest request, HttpServletResponse response) {
        User user = (User) request.getAttribute("currentUser");
        if (user == null || !user.isAdmin()) {
            response.setStatus(403);
            return false;
        }
        return true;
    }

    // ========== 模型管理 ==========

    @GetMapping("/models")
    public Map<String, Object> listModels(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        List<ModelConfig> models = storageService.getAllModelConfigs();
        // 同步厂商显示名/图标（预置厂商）；思考/多模态能力由管理员手动维护，不自动覆盖
        syncModelCapabilities(models);
        // 脱敏 API Key，防止泄露（创建安全副本，不污染原始数据）
        List<ModelConfig> safeModels = models.stream()
                .map(this::toSafeModel)
                .collect(Collectors.toList());
        result.put("success", true);
        result.put("data", safeModels);
        result.put("defaultModelId", storageService.getDefaultModelId());
        return result;
    }

    @PostMapping("/models")
    public Map<String, Object> addModel(@RequestBody ModelConfig config,
                                         HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        ModelConfig saved = storageService.addModelConfig(config);
        result.put("success", true);
        result.put("data", toSafeModel(saved));
        return result;
    }

    @PutMapping("/models/{id}")
    public Map<String, Object> updateModel(@PathVariable String id, @RequestBody ModelConfig config,
                                            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        // 如果 API Key 为空或是脱敏值（含*），保留原来的 Key
        ModelConfig existing = storageService.getModelConfigById(id);
        if (existing != null && (config.getApiKey() == null || config.getApiKey().isEmpty()
                || config.getApiKey().contains("*"))) {
            config.setApiKey(existing.getApiKey());
        }
        config.setId(id);
        storageService.updateModelConfig(config);
        result.put("success", true);
        return result;
    }

    @DeleteMapping("/models/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id,
                                            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        boolean deleted = storageService.deleteModelConfig(id);
        result.put("success", deleted);
        if (!deleted) result.put("message", "模型不存在");
        return result;
    }

    /**
     * 设置全局默认模型（全局唯一，新会话自动选中）。请求体: {"modelId": "xxx"}
     */
    @PutMapping("/models/default")
    public Map<String, Object> setDefaultModel(@RequestBody Map<String, String> body,
                                               HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        String modelId = body == null ? null : body.get("modelId");
        if (modelId == null || modelId.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定模型");
            return result;
        }
        ModelConfig m = storageService.getModelConfigById(modelId);
        if (m == null) {
            result.put("success", false);
            result.put("message", "模型不存在");
            return result;
        }
        storageService.setDefaultModelId(modelId);
        result.put("success", true);
        result.put("message", "已设为默认模型：" + (m.getDisplayName() != null ? m.getDisplayName() : m.getModelId()));
        return result;
    }

    /**
     * 取消全局默认模型
     */
    @DeleteMapping("/models/default")
    public Map<String, Object> clearDefaultModel(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        storageService.clearDefaultModelId();
        result.put("success", true);
        result.put("message", "已取消默认模型");
        return result;
    }

    // ========== 厂商信息 =========
    @GetMapping("/providers")
    public Map<String, Object> listProviders(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
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
                                              HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
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
            result.put("success", true);
            result.put("message", "已更新，影响 " + updated + " 个模型");
            result.put("updatedModels", updated);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 批量快速接入 - 为指定厂商的所有模型创建配置
     * 请求体: { "providerId": "deepseek", "apiKey": "sk-xxx", "selectedModelIds": ["deepseek-v4-pro", "deepseek-v4-flash"], "visibleToAll": true }
     */
    @PostMapping("/models/batch")
    public Map<String, Object> batchAddModels(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }

        String providerId = (String) body.get("providerId");
        String apiKey = (String) body.get("apiKey");
        Boolean visibleToAll = (Boolean) body.get("visibleToAll");
        if (visibleToAll == null) visibleToAll = true;

        if (providerId == null || apiKey == null || apiKey.isEmpty()) {
            result.put("success", false);
            result.put("message", "请提供厂商ID和API Key");
            return result;
        }

        // 查找厂商
        Provider provider = storageService.getAllProviders().stream()
                .filter(p -> p.getId().equals(providerId))
                .findFirst().orElse(null);
        if (provider == null) {
            result.put("success", false);
            result.put("message", "厂商不存在");
            return result;
        }

        // 获取选择的模型ID列表，如果为空则添加所有
        List<String> selectedIds = (List<String>) body.get("selectedModelIds");
        List<ProviderModel> modelsToAdd;
        if (selectedIds != null && !selectedIds.isEmpty()) {
            modelsToAdd = provider.getModels().stream()
                    .filter(m -> selectedIds.contains(m.getId()))
                    .collect(Collectors.toList());
        } else {
            modelsToAdd = provider.getModels();
        }

        // 检查哪些模型已经存在（同providerId + modelId）
        List<ModelConfig> existingConfigs = storageService.getAllModelConfigs();
        Set<String> existingKeys = existingConfigs.stream()
                .map(c -> c.getProviderId() + ":" + c.getModelId())
                .collect(Collectors.toSet());

        int added = 0;
        int skipped = 0;
        for (ProviderModel pm : modelsToAdd) {
            String key = providerId + ":" + pm.getId();
            if (existingKeys.contains(key)) {
                skipped++;
                continue;
            }
            ModelConfig config = new ModelConfig();
            config.setProviderId(providerId);
            // 使用显示名覆盖（图标来自 providers.json）
            config.setProviderName(storageService.getProviderDisplayName(providerId));
            config.setProviderIcon(provider.getIcon());
            config.setModelId(pm.getId());
            config.setDisplayName(pm.getName());
            config.setApiKey(apiKey);
            config.setApiUrl(provider.getDefaultApiUrl());
            config.setProtocol(provider.getProtocol());
            config.setThinkingParamType(provider.getThinkingParamType());
            config.setSupportsThinking(pm.isSupportsThinking());
            config.setSupportsMultimodal(pm.isSupportsMultimodal());
            config.setEnabled(true);
            config.setVisibleToAll(visibleToAll);
            config.setBuiltIn(false);
            storageService.addModelConfig(config);
            added++;
        }

        result.put("success", true);
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("message", "成功添加 " + added + " 个模型" + (skipped > 0 ? "，跳过 " + skipped + " 个已存在模型" : ""));
        return result;
    }

    // ========== 用户管理 ==========

    @GetMapping("/users")
    public Map<String, Object> listUsers(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        List<User> users = storageService.getAllUsers();
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
            m.put("allowedModelIds", u.getAllowedModelIds());
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("data", userList);
        return result;
    }

    @PostMapping("/users")
    public Map<String, Object> addUser(@RequestBody Map<String, Object> body,
                                        HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
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
        result.put("success", true);
        result.put("message", "添加成功");
        return result;
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
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
        String password = (String) body.get("password");
        if (password != null && !password.trim().isEmpty()) {
            user.setPassword(JsonFileStorageService.hashPassword(password));
        }
        storageService.updateUser(user);
        result.put("success", true);
        result.put("message", "保存成功");
        return result;
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable String id,
                                           HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        boolean deleted = storageService.deleteUser(id);
        result.put("success", deleted);
        if (!deleted) result.put("message", "用户不存在或不可删除");
        return result;
    }

    @PutMapping("/users/{id}/permissions")
    public Map<String, Object> updateUserPermissions(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
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
            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        // 校验日期范围（最多 30 天）
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;
        List<UsageLog> logs = storageService.getAllUsageLogs();

        // 过滤
        if (username != null && !username.isEmpty()) {
            logs = logs.stream().filter(l -> username.equals(l.getUsername())).collect(Collectors.toList());
        }
        if (modelName != null && !modelName.isEmpty()) {
            logs = logs.stream().filter(l -> modelName.equals(l.getModelName())).collect(Collectors.toList());
        }
        // 日期范围过滤：startDate <= log.date <= endDate（按 timestamp 前 10 位 yyyy-MM-dd 比较）
        String finalStart = startDate;
        String finalEnd = endDate;
        if ((finalStart != null && !finalStart.isEmpty()) || (finalEnd != null && !finalEnd.isEmpty())) {
            logs = logs.stream().filter(l -> {
                String ts = l.getTimestamp();
                if (ts == null || ts.length() < 10) return false;
                String d = ts.substring(0, 10);
                if (finalStart != null && !finalStart.isEmpty() && d.compareTo(finalStart) < 0) return false;
                if (finalEnd != null && !finalEnd.isEmpty() && d.compareTo(finalEnd) > 0) return false;
                return true;
            }).collect(Collectors.toList());
        }

        // 按时间降序
        logs.sort((a, b) -> {
            if (a.getTimestamp() == null && b.getTimestamp() == null) return 0;
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        int total = logs.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<UsageLog> pageData = logs.subList(fromIndex, toIndex);

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
    public Map<String, Object> getUsageFilters(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        List<UsageLog> logs = storageService.getAllUsageLogs();
        List<String> usernames = logs.stream().map(UsageLog::getUsername).filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
        List<String> modelNames = logs.stream().map(UsageLog::getModelName).filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());

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
            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        // 校验日期范围（最多 30 天）
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        List<UsageLog> logs = storageService.getAllUsageLogs();

        // 过滤
        if (username != null && !username.isEmpty()) {
            logs = logs.stream().filter(l -> username.equals(l.getUsername())).collect(Collectors.toList());
        }
        if (modelName != null && !modelName.isEmpty()) {
            logs = logs.stream().filter(l -> modelName.equals(l.getModelName())).collect(Collectors.toList());
        }
        // 日期范围过滤
        String finalStart = startDate;
        String finalEnd = endDate;
        if ((finalStart != null && !finalStart.isEmpty()) || (finalEnd != null && !finalEnd.isEmpty())) {
            logs = logs.stream().filter(l -> {
                String ts = l.getTimestamp();
                if (ts == null || ts.length() < 10) return false;
                String d = ts.substring(0, 10);
                if (finalStart != null && !finalStart.isEmpty() && d.compareTo(finalStart) < 0) return false;
                if (finalEnd != null && !finalEnd.isEmpty() && d.compareTo(finalEnd) > 0) return false;
                return true;
            }).collect(Collectors.toList());
        }

        // 按 (username, date, modelName) 聚合
        Map<String, List<UsageLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l ->
                        (l.getUsername() != null ? l.getUsername() : "未知") + "|" +
                        (l.getTimestamp() != null ? l.getTimestamp().substring(0, Math.min(10, l.getTimestamp().length())) : "未知") + "|" +
                        (l.getModelName() != null ? l.getModelName() : "未知")
                ));

        List<Map<String, Object>> statsList = new ArrayList<>();
        for (Map.Entry<String, List<UsageLog>> entry : grouped.entrySet()) {
            String[] keys = entry.getKey().split("\\|", 3);
            List<UsageLog> group = entry.getValue();
            Map<String, Object> row = new HashMap<>();
            row.put("username", keys[0]);
            row.put("date", keys[1]);
            row.put("modelName", keys[2]);
            row.put("count", group.size());
            row.put("promptTokens", group.stream().mapToInt(UsageLog::getPromptTokens).sum());
            row.put("completionTokens", group.stream().mapToInt(UsageLog::getCompletionTokens).sum());
            row.put("cachedTokens", group.stream().mapToInt(UsageLog::getCachedTokens).sum());
            row.put("reasoningTokens", group.stream().mapToInt(UsageLog::getReasoningTokens).sum());
            row.put("thinkingCount", group.stream().filter(UsageLog::isDeepThinking).count());
            statsList.add(row);
        }

        // 排序
        statsList.sort((a, b) -> {
            int dateCompare = ((String) b.get("date")).compareTo((String) a.get("date"));
            if (dateCompare != 0) return dateCompare;
            int userCompare = ((String) a.get("username")).compareTo((String) b.get("username"));
            if (userCompare != 0) return userCompare;
            return ((String) a.get("modelName")).compareTo((String) b.get("modelName"));
        });

        int total = statsList.size();
        result.put("success", true);
        result.put("total", total);

        if (getAll) {
            // 图表专用：不分页，一次性返回全量聚合数据
            result.put("data", statsList);
            result.put("page", 1);
            result.put("size", total);
            result.put("totalPages", 1);
        } else {
            // 列表专用：分页
            int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
            int fromIndex = Math.min((page - 1) * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            List<Map<String, Object>> pageData = statsList.subList(fromIndex, toIndex);
            result.put("data", pageData);
            result.put("page", page);
            result.put("size", size);
            result.put("totalPages", totalPages);
        }
        return result;
    }

    // ========== 系统设置（存储模式） ==========

    /**
     * 获取当前存储模式
     * 返回: { "success": true, "data": { "useSqlite": false, "dbFileSize": "2.3MB" } }
     */
    @GetMapping("/settings/storage")
    public Map<String, Object> getStorageSettings(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("useSqlite", storageService.isUseSqlite());
        data.put("dbFileSize", storageService.getDbFileSize());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 切换存储模式
     * 请求体: { "useSqlite": true }
     * 首次开启 SQLite 时自动执行数据迁移
     */
    @PutMapping("/settings/storage")
    public Map<String, Object> setStorageMode(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        Boolean useSqlite = (Boolean) body.get("useSqlite");
        if (useSqlite == null) {
            result.put("success", false);
            result.put("message", "请指定 useSqlite 参数");
            return result;
        }
        try {
            if (useSqlite && !storageService.isUseSqlite()) {
                // 首次开启 SQLite → 自动迁移数据
                String migrationDone = null;
                try {
                    migrationDone = storageService.getSetting("migration_done");
                } catch (Exception ignored) {}
                if (!"true".equals(migrationDone)) {
                    log.info("首次开启SQLite，自动执行数据迁移...");
                    storageService.migrateJsonToSqlite();
                }
            }
            storageService.setUseSqlite(useSqlite);
            result.put("success", true);
            result.put("message", useSqlite ? "已切换到 SQLite 存储" : "已切换到 JSON 文件存储");
        } catch (Exception e) {
            log.error("切换存储模式失败", e);
            result.put("success", false);
            result.put("message", "切换失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 手动触发数据迁移（JSON → SQLite）
     * 返回: { "success": true, "message": "迁移完成：users=12, models=8, logs=1523" }
     */
    @PostMapping("/settings/storage/migrate")
    public Map<String, Object> migrateData(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        try {
            Map<String, Object> stats = storageService.migrateJsonToSqlite();
            result.put("success", true);
            result.put("message", String.format("迁移完成：users=%s, models=%s, logs=%s, chatHistories=%s",
                    stats.get("users"), stats.get("models"), stats.get("logs"), stats.get("chatHistories")));
            result.put("stats", stats);
        } catch (Exception e) {
            log.error("数据迁移失败", e);
            result.put("success", false);
            result.put("message", "迁移失败: " + e.getMessage());
        }
        return result;
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

    /**
     * API Key 脱敏：保留前4位和后4位，中间用星号替代
     * 例如: sk-abcdefghijklmnop → sk-a********mnop
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return "";
        if (apiKey.length() <= 8) {
            // 太短则只保留首尾各2位
            if (apiKey.length() <= 4) return apiKey.charAt(0) + "**" + apiKey.charAt(apiKey.length() - 1);
            return apiKey.substring(0, 2) + "****" + apiKey.substring(apiKey.length() - 2);
        }
        return apiKey.substring(0, 4) + "********" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 同步已存储模型的厂商显示名/图标（预置厂商）。
     * 注意：supportsThinking / supportsMultimodal 不再自动覆盖，由管理员在"模型管理"中通过开关手动设置。
     */
    private void syncModelCapabilities(List<ModelConfig> models) {
        boolean updated = false;
        for (ModelConfig model : models) {
            boolean needUpdate = false;
            // 同步厂商显示名（如有覆盖）
            String displayName = storageService.getProviderDisplayName(model.getProviderId());
            if (displayName != null && !displayName.equals(model.getProviderName())) {
                model.setProviderName(displayName);
                needUpdate = true;
            }
            // 同步厂商图标（来自 providers.json，预置厂商不可改）
            Provider rawProvider = storageService.getProvider(model.getProviderId());
            if (rawProvider != null && rawProvider.getIcon() != null && !rawProvider.getIcon().equals(model.getProviderIcon())) {
                model.setProviderIcon(rawProvider.getIcon());
                needUpdate = true;
            }
            if (needUpdate) {
                storageService.updateModelConfig(model);
                updated = true;
            }
        }
        if (updated) log.info("已自动同步模型厂商名/图标");
    }

    /**
     * 创建 ModelConfig 的安全副本，apiKey 脱敏处理，不污染原始内存数据
     */
    private ModelConfig toSafeModel(ModelConfig m) {
        ModelConfig copy = new ModelConfig();
        copy.setId(m.getId());
        copy.setProviderId(m.getProviderId());
        copy.setProviderName(m.getProviderName());
        copy.setProviderIcon(m.getProviderIcon());
        copy.setModelId(m.getModelId());
        copy.setDisplayName(m.getDisplayName());
        copy.setApiKey(maskApiKey(m.getApiKey()));
        copy.setApiUrl(m.getApiUrl());
        copy.setProtocol(m.getProtocol());
        copy.setThinkingParamType(m.getThinkingParamType());
        copy.setSupportsThinking(m.isSupportsThinking());
        copy.setSupportsMultimodal(m.isSupportsMultimodal());
        copy.setEnabled(m.isEnabled());
        copy.setVisibleToAll(m.getVisibleToAll());
        copy.setBuiltIn(m.isBuiltIn());
        copy.setCreatedAt(m.getCreatedAt());
        return copy;
    }
}

