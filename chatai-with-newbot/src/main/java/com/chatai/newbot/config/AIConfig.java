package com.chatai.newbot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static com.chatai.newbot.constant.AiConstant.*;

@Component
@Data
public class AIConfig {
    /**
     * deepseek
     */
    @Value("${ai.deepseek-key}")
    private String deepseekKey;
    @Value("${ai.deepseek-url}")
    private String deepseekUrl;

    @Autowired
    private Environment env;

    /**
     * 动态加载模型配置
     * @param model
     * @return
     */
    public Map<String, String> getModelConfig(String model) {
        Map<String, String> config = new HashMap<>();
        config.put(API_KEY, env.getProperty(model + AI_API_KEY));
        config.put(API_URL, env.getProperty(model + AI_API_URL));
        config.put(APP_ID, env.getProperty(model + AI_APP_ID));
        return config;
    }
}