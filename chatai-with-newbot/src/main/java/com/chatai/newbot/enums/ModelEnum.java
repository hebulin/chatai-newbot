package com.chatai.newbot.enums;

public enum ModelEnum {
    // 模型源相关枚举
    DEEP_THINK_ALI_SOURCE("deepthink", "qwq-32b"),
    DEEPSEEK_V3_ALI_SOURCE("ali-deepseek","ali-deepseek"),
    DEEPSEEK_V3_OFFICIAL_SOURCE("deepseek","deepseek-chat"),

    // Qwen系列模型枚举
    QWEN_MAX_LATEST("qwen-max","qwen-max-latest"),
    QWEN_PLUS_LATEST("qwen-plus","qwen-plus-latest"),
    QWEN_TURBO_LATEST("qwen-turbo","qwen-turbo-latest");

    // 私有字段存储实际值
    private final String modelName;
    private final String modelValue;

    ModelEnum(String modelName, String modelValue) {
        this.modelName = modelName;
        this.modelValue = modelValue;
    }

    public static String getModelValueByName(String modelName) {
        for (ModelEnum modelEnum : ModelEnum.values()) {
            if (modelEnum.modelName.equals(modelName)) {
                return modelEnum.modelValue;
            }
        }
        return null;
    }
}
