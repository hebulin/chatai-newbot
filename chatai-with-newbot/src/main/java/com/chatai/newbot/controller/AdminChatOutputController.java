package com.chatai.newbot.controller;

import com.chatai.newbot.config.AdminSupport;
import com.chatai.newbot.service.ModelConfigRepository;
import com.chatai.newbot.service.OutputTokenPolicy;
import com.chatai.newbot.service.StorageManager;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import com.chatai.newbot.exception.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统设置中的全局输出配置与模型覆盖专用接口。 */
@RestController
@RequestMapping("/api/admin/settings/chat-output")
public class AdminChatOutputController {
    private final StorageManager storage;
    private final ModelConfigRepository models;
    private final AdminSupport admin;

    /** 注入设置存储、模型列更新仓储与管理员鉴权。 */
    public AdminChatOutputController(StorageManager storage, ModelConfigRepository models, AdminSupport admin) {
        this.storage = storage;
        this.models = models;
        this.admin = admin;
    }

    /** 返回全局配置与模型可空覆盖值，不暴露密钥或其他不相关配置。 */
    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        admin.requireAdmin(request);
        int global = OutputTokenPolicy.globalLimit(storage.getSetting(OutputTokenPolicy.GLOBAL_SETTING_KEY));
        var rows = storage.getAllModelConfigs().stream().map(model -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", model.getId());
            row.put("name", model.getDisplayName() == null ? model.getModelId() : model.getDisplayName());
            row.put("providerName", model.getProviderName());
            row.put("protocol", model.getProtocol());
            row.put("maxOutputTokens", model.getMaxOutputTokens());
            return row;
        }).toList();
        var configured = rows.stream().filter(row -> row.get("maxOutputTokens") != null).toList();
        return Map.of("success", true, "data", Map.of(
                "globalMaxOutputTokens", global, "models", configured, "availableModels", rows));
    }

    /** 保存全局值，0 为不限，缺失或非法值明确拒绝。 */
    @PutMapping
    public Map<String, Object> saveGlobal(@RequestBody JsonNode body, HttpServletRequest request) {
        admin.requireAdmin(request);
        int limit = parseLimit(body, false);
        storage.setSetting(OutputTokenPolicy.GLOBAL_SETTING_KEY, String.valueOf(limit));
        admin.audit(request, "settings.chatOutput", "全局输出上限=" + limit);
        return Map.of("success", true);
    }

    /** 保存单个模型覆盖；显式 null 表示清空并恢复继承全局。 */
    @PutMapping("/models/{id}")
    public Map<String, Object> saveModel(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        admin.requireAdmin(request);
        Integer limit = parseLimit(body, true);
        if (!models.updateOutputLimit(id, limit)) throw new ApiException(404, "模型不存在");
        admin.audit(request, "settings.chatOutput.model", "模型 " + id + " 输出上限=" + (limit == null ? "继承全局" : limit));
        return Map.of("success", true);
    }

    /** 校验非负整数和可空语义，防止小数、溢出或缺失字段被静默归零。 */
    private Integer parseLimit(JsonNode body, boolean nullable) {
        JsonNode value = body == null ? null : body.get("maxOutputTokens");
        if (value != null && value.isNull() && nullable) return null;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new ApiException(400, "输出上限必须为非负整数，模型空值表示继承全局");
        }
        return value.intValue();
    }
}
