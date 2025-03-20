package com.chatai.newbot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.chatai.newbot.constant.AiConstant.*;

@Component
@Data
public class AIConfig {
    @Value("${ai.ali-api-key}")
    private String aliApiKey;

    @Value("${ai.ali-api-url}")
    private String aliApiUrl;

    @Value("${ai.ali-app-id}")
    private String aliAppId;

    /**
     * deepseek
     */
    @Value("${ai.deepseek-key}")
    private String deepseekKey;
    @Value("${ai.deepseek-url}")
    private String deepseekUrl;
    @Value("${ai.deepseek-model}")
    private String deepseekModel;

    // 动态加载模型配置
    public Map<String, String> getModelConfig(String model) {
        Map<String, String> config = new HashMap<>();
        config.put(API_KEY, System.getProperty(model + AI_API_KEY));
        config.put(API_URL, System.getProperty(model + AI_API_URL));
        config.put(APP_ID, System.getProperty(model + AI_APP_ID));
        return config;
    }
}