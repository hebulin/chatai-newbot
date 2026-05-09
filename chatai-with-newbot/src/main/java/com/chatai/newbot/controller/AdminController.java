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
        result.put("success", true);
        result.put("data", storageService.getAllModelConfigs());
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
        result.put("data", saved);
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
            m.put("allowedModelIds", u.getAllowedModelIds());
            return m;
        }).collect(Collectors.toList());

        result.put("success", true);
        result.put("data", userList);
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

    @GetMapping("/usage")
    public Map<String, Object> getUsageLogs(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        if (!checkAdmin(request, response)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        result.put("success", true);
        result.put("data", storageService.getUsageLogs());
        return result;
    }
}
