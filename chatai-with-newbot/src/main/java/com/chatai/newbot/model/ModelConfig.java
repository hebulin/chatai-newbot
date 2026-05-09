package com.chatai.newbot.model;

import lombok.Data;

/**
 * 已配置的模型实例（用户/管理员添加的可用模型）
 */
@Data
public class ModelConfig {
    private String id;              // 唯一ID (UUID)
    private String providerId;      // 厂商ID，如 "deepseek"，自定义为 "custom"
    private String providerName;    // 厂商显示名称，如 "DeepSeek"
    private String providerIcon;    // 厂商图标emoji
    private String modelId;         // 模型API标识，如 "deepseek-chat"
    private String displayName;     // 前端显示名称
    private String apiKey;          // API密钥
    private String apiUrl;          // API地址（完整基础URL，如 https://api.deepseek.com/v1）
    private String protocol;        // 协议类型 "openai" / "custom"
    private String thinkingParamType; // 思考模式参数类型: deepseek/qwen/kimi/doubao/default
    private boolean supportsThinking; // 是否支持思考模式
    private boolean enabled;        // 是否启用
    private Boolean visibleToAll;    // 是否对所有用户可见，默认true
    private boolean builtIn;        // 是否为系统内置
    private String createdAt;       // 创建时间
}
