package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatAttachment;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.PromptPreset;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;
import org.springframework.stereotype.Component;

import java.util.List;

/** 负责系统提示词、附件文本和消息数量上限等上下文组装规则。 */
@Component
public class ChatContextAssembler {
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";
    private final StorageManager storageService;
    private final FileStorageService fileStorageService;
    private final ContextBudgetService contextBudgetService;

    /** 创建聊天上下文组装器。 */
    public ChatContextAssembler(StorageManager storageService, FileStorageService fileStorageService,
                                ContextBudgetService contextBudgetService) {
        this.storageService = storageService;
        this.fileStorageService = fileStorageService;
        this.contextBudgetService = contextBudgetService;
    }

    /** 解析会话绑定预设、全局预设、旧提示词与默认提示词的优先级。 */
    public String resolveSystemPrompt(ChatRequest request, UsageLog usageLog) {
        String systemPrompt = DEFAULT_SYSTEM_PROMPT;
        if (usageLog == null || usageLog.getUserId() == null) return systemPrompt;
        User currentUser = storageService.getUserById(usageLog.getUserId());
        if (currentUser == null) return systemPrompt;
        String presetId = request == null ? null : request.getPromptPresetId();
        String builtinContent = BuiltinAgents.contentById(presetId);
        if (builtinContent != null) return builtinContent;
        if (presetId != null && !presetId.trim().isEmpty() && currentUser.getPromptPresets() != null) {
            for (PromptPreset preset : currentUser.getPromptPresets()) {
                if (preset != null && presetId.equals(preset.getId()) && hasText(preset.getContent())) {
                    return preset.getContent().trim();
                }
            }
        }
        if (currentUser.getPromptPresets() != null) {
            for (PromptPreset preset : currentUser.getPromptPresets()) {
                if (preset != null && preset.isEnabled() && hasText(preset.getContent())) {
                    return preset.getContent().trim();
                }
            }
        }
        if (hasText(currentUser.getSystemPrompt())) return currentUser.getSystemPrompt().trim();
        return systemPrompt;
    }

    /** 按全局条数上限保留最近的消息。 */
    public List<NewBotMessage> applyMessageLimit(List<NewBotMessage> messages) {
        if (messages == null) return null;
        int max = storageService.getContextMaxMessages();
        if (max <= 0 || messages.size() <= max) return messages;
        return messages.subList(messages.size() - max, messages.size());
    }

    /** 将附件文档文本放在用户内容前，并对历史附件执行预算裁剪。 */
    public String contentWithAttachments(NewBotMessage message, String userId, boolean currentTurn) {
        String content = message.getContent() == null ? "" : message.getContent();
        if (message.getAttachments() == null || message.getAttachments().isEmpty()) return message.getContent();
        StringBuilder result = new StringBuilder();
        for (ChatAttachment attachment : message.getAttachments()) {
            if (attachment == null) continue;
            String name = attachment.getName() == null ? "未命名文档" : attachment.getName();
            String text = fileStorageService.readDocumentText(attachment.getUrl(), userId);
            text = contextBudgetService.clipAttachmentText(text, currentTurn);
            result.append("【附件文档：").append(name).append("】\n");
            result.append(text != null ? text : "（该附件内容已失效，无法读取）");
            result.append("\n【附件文档结束】\n\n");
        }
        result.append(content);
        return result.toString();
    }

    /** 返回最后一条非空用户消息，供联网检索使用。 */
    public String lastUserText(List<NewBotMessage> messages) {
        if (messages == null) return null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            NewBotMessage message = messages.get(index);
            if (message != null && "user".equals(message.getRole()) && hasText(message.getContent())) {
                return message.getContent().trim();
            }
        }
        return null;
    }

    /** 判断字符串是否含非空白内容。 */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
