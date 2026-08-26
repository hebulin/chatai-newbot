package com.chatai.newbot.controller;

import com.chatai.newbot.config.AdminSupport;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.Provider;
import com.chatai.newbot.model.ProviderModel;
import com.chatai.newbot.service.ModelHealthCheckService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.UnifiedChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 后台管理 - 模型管理模块（从 AdminController 拆分而来，路径前缀保持 /api/admin 不变）
 * 包含：模型 CRUD、批量接入、批量删除、连通性测试、默认模型设置
 */
@RestController
@RequestMapping("/api/admin")
public class AdminModelController {
    private static final Logger log = LoggerFactory.getLogger(AdminModelController.class);
    private final StorageManager storageService;
    private final UnifiedChatService unifiedChatService;
    private final ModelHealthCheckService healthCheckService;
    private final AdminSupport admin;

    public AdminModelController(StorageManager storageService, UnifiedChatService unifiedChatService,
                                ModelHealthCheckService healthCheckService, AdminSupport admin) {
        this.storageService = storageService;
        this.unifiedChatService = unifiedChatService;
        this.healthCheckService = healthCheckService;
        this.admin = admin;
    }

    @GetMapping("/models")
    public Map<String, Object> listModels(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        List<ModelConfig> models = storageService.getAllModelConfigs();
        // 脱敏 API Key，防止泄露（创建安全副本，不污染原始数据）；
        // 厂商名/图标同步已改为启动时一次性执行 + 改名时实时同步，读接口不再写库
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
                                         HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        ModelConfig saved = storageService.addModelConfig(config);
        admin.audit(request, "model.add", "新增模型 " + (saved.getDisplayName() != null ? saved.getDisplayName() : saved.getModelId()));
        result.put("success", true);
        result.put("data", toSafeModel(saved));
        return result;
    }

    @PutMapping("/models/{id}")
    public Map<String, Object> updateModel(@PathVariable String id, @RequestBody ModelConfig config,
                                            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        // 如果 API Key 为空或是脱敏值（含*），保留原来的 Key
        ModelConfig existing = storageService.getModelConfigById(id);
        if (existing != null && (config.getApiKey() == null || config.getApiKey().isEmpty()
                || config.getApiKey().contains("*"))) {
            config.setApiKey(existing.getApiKey());
        }
        config.setId(id);
        storageService.updateModelConfig(config);
        admin.audit(request, "model.update", "更新模型 " + (config.getDisplayName() != null ? config.getDisplayName() : id));
        result.put("success", true);
        return result;
    }

    @DeleteMapping("/models/{id}")
    public Map<String, Object> deleteModel(@PathVariable String id,
                                            HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        boolean deleted = storageService.deleteModelConfig(id);
        if (deleted) {
            admin.audit(request, "model.delete", "删除模型 " + id);
        }
        result.put("success", deleted);
        if (!deleted) result.put("message", "模型不存在");
        return result;
    }

    /**
     * 批量删除模型。请求体: {"ids": ["..."]}
     */
    @PostMapping("/models/batch-delete")
    public Map<String, Object> batchDeleteModels(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Object idsObj = body == null ? null : body.get("ids");
        if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要删除的模型");
            return result;
        }
        int deleted = 0;
        for (Object idObj : ids) {
            if (idObj instanceof String id && !id.isEmpty() && storageService.deleteModelConfig(id)) {
                deleted++;
            }
        }
        log.info("后台批量删除模型：请求 {} 条，实际删除 {} 条", ids.size(), deleted);
        admin.audit(request, "model.batchDelete", "批量删除模型 " + deleted + " 个");
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * 模型连通性测试：向厂商 API 发一条最小请求，验证 API Key/URL/模型ID 是否可用
     */
    @PostMapping("/models/{id}/test")
    public Map<String, Object> testModel(@PathVariable String id,
                                          HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> res = unifiedChatService.testConnection(id);
        ModelConfig m = storageService.getModelConfigById(id);
        admin.audit(request, "model.test", "测试模型连通性 " + (m != null && m.getDisplayName() != null ? m.getDisplayName() : id));
        return res;
    }

    /**
     * 设置全局默认模型（全局唯一，新会话自动选中）。请求体: {"modelId": "xxx"}
     */
    @PutMapping("/models/default")
    public Map<String, Object> setDefaultModel(@RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
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
        admin.audit(request, "model.setDefault", "设置默认模型 " + (m.getDisplayName() != null ? m.getDisplayName() : m.getModelId()));
        result.put("success", true);
        result.put("message", "已设为默认模型：" + (m.getDisplayName() != null ? m.getDisplayName() : m.getModelId()));
        return result;
    }

    /**
     * 取消全局默认模型
     */
    @DeleteMapping("/models/default")
    public Map<String, Object> clearDefaultModel(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        storageService.clearDefaultModelId();
        admin.audit(request, "model.clearDefault", "取消默认模型");
        result.put("success", true);
        result.put("message", "已取消默认模型");
        return result;
    }

    // ========== 系统设置（模型健康检查） ==========

    /**
     * 获取模型健康检查设置（总开关与检查间隔的运行值：后台设置优先于 yml 默认）。
     * 返回: { "success": true, "data": { "enabled": true, "intervalMinutes": 360 } }
     */
    @GetMapping("/settings/health-check")
    public Map<String, Object> getHealthCheckSettings(HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", healthCheckService.isEnabledEffective());
        data.put("intervalMinutes", healthCheckService.getIntervalMinutesEffective());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 保存模型健康检查设置，保存后下一个调度心跳（1 分钟内）即按新配置生效，无需重启。
     * 请求体: { "enabled": true, "intervalMinutes": 360 }（两个字段均可选，缺省不修改）
     */
    @PutMapping("/settings/health-check")
    public Map<String, Object> setHealthCheckSettings(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        if (body == null || body.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要保存的健康检查设置");
            return result;
        }
        try {
            if (body.get("enabled") instanceof Boolean b) {
                storageService.setSetting("health_check_enabled", String.valueOf(b));
            }
            if (body.get("intervalMinutes") instanceof Number n) {
                long interval = n.longValue();
                if (interval < 1 || interval > 10080) {
                    result.put("success", false);
                    result.put("message", "检查间隔范围应为 1 ~ 10080 分钟");
                    return result;
                }
                storageService.setSetting("health_check_interval_minutes", String.valueOf(interval));
            }
            admin.audit(request, "健康检查设置", "保存模型健康检查设置");
            result.put("success", true);
            result.put("message", "健康检查设置已保存");
        } catch (Exception e) {
            log.error("保存健康检查设置失败", e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 批量快速接入 - 为指定厂商的所有模型创建配置
     * 请求体: { "providerId": "deepseek", "apiKey": "sk-xxx", "selectedModelIds": ["deepseek-v4-pro", "deepseek-v4-flash"], "visibleToAll": true }
     */
    @PostMapping("/models/batch")
    @SuppressWarnings("unchecked")
    public Map<String, Object> batchAddModels(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        admin.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();

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

        admin.audit(request, "model.batchAdd", "批量接入厂商 " + providerId + " 模型 " + added + " 个");
        result.put("success", true);
        result.put("added", added);
        result.put("skipped", skipped);
        result.put("message", "成功添加 " + added + " 个模型" + (skipped > 0 ? "，跳过 " + skipped + " 个已存在模型" : ""));
        return result;
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
        copy.setHealthCheckEnabled(m.getHealthCheckEnabled());
        copy.setBuiltIn(m.isBuiltIn());
        copy.setCreatedAt(m.getCreatedAt());
        copy.setTestLatencyMs(m.getTestLatencyMs());
        copy.setTestSpeed(m.getTestSpeed());
        copy.setTestedAt(m.getTestedAt());
        copy.setInputPriceCny(m.getInputPriceCny());
        copy.setOutputPriceCny(m.getOutputPriceCny());
        copy.setCachedPriceCny(m.getCachedPriceCny());
        copy.setReasoningPriceCny(m.getReasoningPriceCny());
        return copy;
    }
}
