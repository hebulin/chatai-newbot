package com.chatai.newbot.controller;

import com.chatai.newbot.model.*;
import com.chatai.newbot.service.FileStorageService;
import com.chatai.newbot.service.UnifiedChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final UnifiedChatService chatService;
    private final FileStorageService storageService;

    public ChatController(UnifiedChatService chatService, FileStorageService storageService) {
        this.chatService = chatService;
        this.storageService = storageService;
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
            item.put("thinkingParamType", m.getThinkingParamType());
            modelList.add(item);
        }

        result.put("success", true);
        result.put("data", modelList);
        return result;
    }
}
