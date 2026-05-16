package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final FileStorageService storageService;

    public AdminController(FileStorageService storageService) {
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
        // 脱敏 API Key，防止泄露（创建安全副本，不污染原始数据）
        List<ModelConfig> safeModels = models.stream()
                .map(this::toSafeModel)
                .collect(Collectors.toList());
        result.put("success", true);
        result.put("data", safeModels);
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

    // ========== 厂商信息 ==========

    @GetMapping("/providers")
    public Map<String, Object> listProviders(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        result.put("success", true);
        result.put("data", storageService.getAllProviders());
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
            config.setProviderName(provider.getName());
            config.setProviderIcon(provider.getIcon());
            config.setModelId(pm.getId());
            config.setDisplayName(pm.getName());
            config.setApiKey(apiKey);
            config.setApiUrl(provider.getDefaultApiUrl());
            config.setProtocol(provider.getProtocol());
            config.setThinkingParamType(provider.getThinkingParamType());
            config.setSupportsThinking(pm.isSupportsThinking());
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
            user.setPassword(FileStorageService.hashPassword(password));
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
            @RequestParam(required = false) String date,
            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        List<UsageLog> logs = storageService.getAllUsageLogs();

        // 过滤
        if (username != null && !username.isEmpty()) {
            logs = logs.stream().filter(l -> username.equals(l.getUsername())).collect(Collectors.toList());
        }
        if (modelName != null && !modelName.isEmpty()) {
            logs = logs.stream().filter(l -> modelName.equals(l.getModelName())).collect(Collectors.toList());
        }
        if (date != null && !date.isEmpty()) {
            logs = logs.stream().filter(l -> l.getTimestamp() != null && l.getTimestamp().startsWith(date)).collect(Collectors.toList());
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
     */
    @GetMapping("/usage/stats")
    public Map<String, Object> getUsageStats(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String date,
            HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }

        List<UsageLog> logs = storageService.getAllUsageLogs();

        // 过滤
        if (username != null && !username.isEmpty()) {
            logs = logs.stream().filter(l -> username.equals(l.getUsername())).collect(Collectors.toList());
        }
        if (modelName != null && !modelName.isEmpty()) {
            logs = logs.stream().filter(l -> modelName.equals(l.getModelName())).collect(Collectors.toList());
        }
        if (date != null && !date.isEmpty()) {
            logs = logs.stream().filter(l -> l.getTimestamp() != null && l.getTimestamp().startsWith(date)).collect(Collectors.toList());
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

        // 分页
        int total = statsList.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> pageData = statsList.subList(fromIndex, toIndex);

        result.put("success", true);
        result.put("data", pageData);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        return result;
    }

    // ========== 工具方法 ==========

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
        copy.setEnabled(m.isEnabled());
        copy.setVisibleToAll(m.getVisibleToAll());
        copy.setBuiltIn(m.isBuiltIn());
        copy.setCreatedAt(m.getCreatedAt());
        return copy;
    }
}
