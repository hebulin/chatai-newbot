package com.chatai.newbot.model;

import lombok.Data;

/**
 * 使用记录
 */
@Data
public class UsageLog {
    /** 一次模型调用的唯一请求 ID，用于并发流结束时精确更新对应记录 */
    private String requestId;
    private String userId;
    private String username;
    private String modelId;
    private String modelName;
    private String timestamp;
    private int promptTokens;
    private int completionTokens;
    private int cachedTokens;
    private int reasoningTokens;
    private boolean deepThinking;
    /** 本次调用的人民币成本快照；旧记录为空时按模型当前人民币单价动态估算 */
    private Double costCny;
}
