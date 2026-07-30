package com.chatai.newbot.model;

import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    private String model;
    private String modelConfigId; // 对应 ModelConfig 的ID
    private List<NewBotMessage> messages;
    private boolean stream = true;
    private double temperature = 0.7;
    private int max_tokens = 6000;
    private boolean deepThinking;
    private boolean webSearch; // 是否开启联网搜索（Tavily）
    private String promptPresetId; // 会话绑定的提示词预设 ID（角色），为空时使用用户全局启用的预设
}
