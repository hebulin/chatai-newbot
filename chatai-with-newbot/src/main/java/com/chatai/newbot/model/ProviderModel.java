package com.chatai.newbot.model;

import lombok.Data;

/**
 * 厂商支持的模型定义
 */
@Data
public class ProviderModel {
    private String id;          // 模型API标识，如 "deepseek-chat"
    private String name;        // 显示名称，如 "DeepSeek-V3"
    private boolean supportsThinking; // 是否支持思考模式
}
