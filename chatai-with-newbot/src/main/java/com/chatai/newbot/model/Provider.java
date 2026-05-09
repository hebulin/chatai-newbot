package com.chatai.newbot.model;

import lombok.Data;
import java.util.List;

/**
 * 厂商定义（系统内置的厂商信息）
 */
@Data
public class Provider {
    private String id;              // 厂商标识，如 "deepseek"
    private String name;            // 厂商显示名称，如 "DeepSeek"
    private String icon;            // 厂商图标emoji
    private String defaultApiUrl;   // 默认API地址
    private String protocol;        // 协议类型 "openai"
    private String thinkingParamType; // 思考模式参数类型: deepseek/qwen/kimi/doubao/default
    private List<ProviderModel> models; // 该厂商支持的模型列表
}
