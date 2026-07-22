package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.ChatHistoryService;
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

    public ChatController(UnifiedChatService chatService, StorageManager storageService,
                          ChatHistoryService chatHistoryService) {
        this.chatService = chatService;
        this.storageService = storageService;
        this.chatHistoryService = chatHistoryService;
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

        // 权限检查：visibleToAll 或 admin 或 在用户的allowedModelIds中
        if (!Boolean.TRUE.equals(config.getVisibleToAll()) && !user.isAdmin()
                && !user.getAllowedModelIds().contains(config.getId())) {
            return Flux.just("{\"error\":{\"message\":\"无权使用该模型\",\"type\":\"permission_error\"}}");
        }

        // 记录使用
        UsageLog usageLog = new UsageLog();
        usageLog.setUserId(user.getId());
        usageLog.setUsername(user.getUsername());
        usageLog.setModelId(config.getId());
        usageLog.setModelName(config.getDisplayName());
        usageLog.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        usageLog.setDeepThinking(request.isDeepThinking());
        storageService.addUsageLog(usageLog);

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

        // 自动同步多模态/思考支持状态（从 providers.json 更新旧数据）
        syncModelCapabilities(models);

        // 构建返回数据 - 不暴露apiKey
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
        return result;
    }

    /**
     * 同步已存储模型的厂商显示名/图标（预置厂商）。
     * 注意：supportsThinking / supportsMultimodal 不再自动覆盖，由管理员在"模型管理"中手动维护。
     */
    private void syncModelCapabilities(List<ModelConfig> models) {
        List<Provider> providers = storageService.getAllProviders();
        boolean updated = false;
        for (ModelConfig model : models) {
            Provider provider = providers.stream()
                    .filter(p -> p.getId().equals(model.getProviderId()))
                    .findFirst().orElse(null);
            if (provider == null) continue;
            ProviderModel pm = (provider.getModels() == null) ? null :
                    provider.getModels().stream().filter(m -> m.getId().equals(model.getModelId())).findFirst().orElse(null);
            if (pm == null) continue;
            boolean needUpdate = false;
            // 同步显示名覆盖
            String displayName = storageService.getProviderDisplayName(model.getProviderId());
            if (displayName != null && !displayName.equals(model.getProviderName())) {
                model.setProviderName(displayName);
                needUpdate = true;
            }
            // 同步厂商图标（来自 providers.json）
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

    // ========== 会话历史同步（多端统一） ==========

    /**
     * 获取当前用户的会话历史
     */
    @GetMapping("/chat/history")
    public Map<String, Object> getChatHistory(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> history = chatHistoryService.loadChatHistory(user.getId());
        history.put("success", true);
        return history;
    }

    /**
     * 保存当前用户的会话历史
     * 请求体格式: { "lastChatId": "xxx", "chats": {...}, "deletedChatIds": [...] }
     */
    @PostMapping("/chat/history")
    public Map<String, Object> saveChatHistory(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        try {
            chatHistoryService.saveChatHistory(user.getId(), body);
            result.put("success", true);
        } catch (Exception e) {
            log.error("保存会话历史失败: userId={}", user.getId(), e);
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
        }
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
}

