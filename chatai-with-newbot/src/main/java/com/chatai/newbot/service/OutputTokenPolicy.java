package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.exception.ApiException;
import java.util.List;
import java.util.Map;

/** 统一解析模型输出上限，保证管理员、普通用户、两种协议和配额预占使用同一预算。 */
public final class OutputTokenPolicy {
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;
    public static final String GLOBAL_SETTING_KEY = "chat_max_output_tokens";
    public static final int DEFAULT_CONTEXT_WINDOW = 32000;
    public static final String CONTEXT_SETTING_KEY = "chat_context_window";

    /** 工具类不允许实例化。 */
    private OutputTokenPolicy() { }

    /** 使用模型配置或缺省值；0 为不限，客户端仍可主动指定更小的输出预算。 */
    public static int resolve(ChatRequest request, ModelConfig model) {
        return resolve(request, model, null);
    }

    /** 读取全局缺省值；0 为不限，损坏或负数配置回退到缺省 16384。 */
    public static int globalLimit(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : DEFAULT_MAX_OUTPUT_TOKENS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_OUTPUT_TOKENS;
        }
    }

    /** 模型非空覆盖优先（含 0 不限），空值继承全局；客户端只能缩小有效预算。 */
    public static int resolve(ChatRequest request, ModelConfig model, String globalSetting) {
        int modelLimit = model != null && model.getMaxOutputTokens() != null && model.getMaxOutputTokens() >= 0
                ? model.getMaxOutputTokens() : globalLimit(globalSetting);
        if (modelLimit == 0 && request.getMax_tokens() <= 0) return 0;
        int requested = request.getMax_tokens() > 0 ? request.getMax_tokens() : modelLimit;
        return modelLimit > 0 ? Math.min(requested, modelLimit) : requested;
    }

    /** 上下文必须有正容量；旧模型的 0 与空值均继承全局设置。 */
    public static int contextWindow(ModelConfig model, String globalSetting) {
        if (model != null && model.getContextWindow() != null && model.getContextWindow() > 0) {
            return model.getContextWindow();
        }
        try {
            int parsed = Integer.parseInt(globalSetting);
            return parsed > 0 ? parsed : DEFAULT_CONTEXT_WINDOW;
        } catch (NumberFormatException e) {
            return DEFAULT_CONTEXT_WINDOW;
        }
    }

    /**
     * 两种协议统一按最终输入限制输出，包含后来注入的附件、搜索和摘要。
     * 输出为 0 时不额外设固定上限，仅使用配置的上下文剩余容量。
     */
    public static int resolveForBody(ChatRequest request, ModelConfig model, Map<String, Object> body,
                                     String globalOutput, String globalContext) {
        int limit = resolve(request, model, globalOutput);
        long remaining = (long) contextWindow(model, globalContext) - estimateInput(body);
        if (remaining <= 0) {
            throw new ApiException(400, "当前输入（含附件、搜索和摘要）已占满上下文，请精简输入或增大上下文大小");
        }
        return (int) (limit > 0 ? Math.min(limit, remaining) : remaining);
    }

    /** 估算最终协议输入，避免把图片的 base64 编码误当作文本。 */
    static long estimateInput(Map<String, Object> body) {
        ContextBudgetService estimator = new ContextBudgetService();
        long input = estimateContent(body.get("system"), estimator);
        if (body.get("messages") instanceof List<?> messages) {
            for (Object item : messages) {
                if (item instanceof Map<?, ?> message) input += 4L + estimateContent(message.get("content"), estimator);
            }
        }
        return input;
    }

    /** 估算最终协议内容，图片按占位计入，避免将 base64 字符串误算成文本 Token。 */
    private static long estimateContent(Object content, ContextBudgetService estimator) {
        if (content instanceof String text) return estimator.estimateTokens(text);
        if (content instanceof List<?> blocks) {
            long tokens = 0;
            for (Object block : blocks) tokens += estimateContent(block, estimator);
            return tokens;
        }
        if (content instanceof Map<?, ?> block) {
            if ("image".equals(block.get("type")) || "image_url".equals(block.get("type"))) return 1100;
            return estimateContent(block.get("text"), estimator);
        }
        return 0;
    }
}
