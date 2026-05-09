package com.chatai.newbot.model;

import lombok.Data;

/**
 * 使用记录
 */
@Data
public class UsageLog {
    private String userId;
    private String username;
    private String modelId;
    private String modelName;
    private String timestamp;
    private int promptTokens;
    private int completionTokens;
    private boolean deepThinking;
}
