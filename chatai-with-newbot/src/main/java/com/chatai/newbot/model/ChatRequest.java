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
}
