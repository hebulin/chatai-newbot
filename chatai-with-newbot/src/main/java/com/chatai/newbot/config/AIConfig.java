package com.chatai.newbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIConfig {
    /**
     * deepseek
     */
    private String deepseekKey;
    private String deepseekUrl;
    private String deepseekModel;
    /**
     * 阿里
     */
    private String aliApiKey;
    private String aliApiUrl;
    private String aliAppId;
    private String aliModel;
}