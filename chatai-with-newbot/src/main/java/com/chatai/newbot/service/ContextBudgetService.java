package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 长对话上下文预算管理服务：
 * 将主要控制方式从"固定消息条数"升级为按模型上下文容量管理 Token 预算，
 * 预算覆盖系统提示词、当前问题、历史消息与附件文本，并预留输出及思考预算。
 * 无法精确计量时采用保守估算（CJK 按字计、英文按词元比例、整体加安全系数），
 * 保证不超模型实际上下文上限；历史按完整对话轮次从最新往回保留，不破坏角色顺序。
 */
@Service
public class ContextBudgetService {
    private static final Logger log = LoggerFactory.getLogger(ContextBudgetService.class);

    /** 模型默认上下文容量（未配置 contextWindow 时） */
    public static final int DEFAULT_CONTEXT_WINDOW = 32000;
    /** 预留输出预算（未显式 max_tokens 时） */
    private static final int DEFAULT_OUTPUT_RESERVE = 4096;
    /** 估算安全系数（无法精确计量时的保守上浮） */
    private static final double SAFETY_FACTOR = 1.15;
    /** 每条消息的固定结构开销（role/分隔等） */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    /** 历史消息中附件文本的截断长度（仅当前轮附件全文注入） */
    private static final int HISTORY_ATTACHMENT_CLIP = 500;

    /**
     * 保守估算文本的 Token 数：
     * CJK（中日韩）字符按 1 token/字，ASCII 按 1 token/4 字符，其余按 1 token/2 字符；
     * 整体乘以安全系数上浮，确保不超模型真实上限。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) ascii++;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
                    || (c >= 0x3000 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFFEF)) cjk++;
            else other++;
        }
        double raw = cjk + ascii / 4.0 + other / 2.0;
        return (int) Math.ceil(raw * SAFETY_FACTOR);
    }

    /** 估算单条消息（含角色开销与附件引用占位）的 Token 数 */
    public int estimateMessageTokens(NewBotMessage msg) {
        int tokens = MESSAGE_OVERHEAD_TOKENS + estimateTokens(msg.getContent());
        // 图片按固定占位估算（无法精确计量多模态图片 Token，取保守值）
        if (msg.getImages() != null) {
            tokens += msg.getImages().size() * 1100;
        }
        if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
            // 附件文本由后端读回注入，此处按截断上限估算（当前轮全文在调用方另行计入）
            tokens += msg.getAttachments().size() * HISTORY_ATTACHMENT_CLIP;
        }
        return tokens;
    }

    /** 预算裁剪结果 */
    public static class BudgetResult {
        /** 裁剪后的消息列表（保留最近完整对话轮次） */
        public List<NewBotMessage> messages;
        /** 被裁掉的最早历史条数 */
        public int droppedCount;
        /** 裁剪后预计输入 Token 总量（含系统提示词） */
        public int estimatedPromptTokens;
        /** 当前输入本身是否超限（true 时调用方应明确报错而不是静默截断） */
        public boolean inputOverLimit;
        /** 模型上下文容量 */
        public int contextWindow;
    }

    /**
     * 按模型上下文容量裁剪历史消息：
     * 预算 = contextWindow - 输出预留(max_tokens) - 系统提示词估算；
     * 从最新往旧保留完整对话轮次（user/assistant 成对，避免破坏角色顺序）；
     * 最后一条用户消息（当前问题）必须保留——其本身超限时标记 inputOverLimit，由调用方明确提示。
     * @param messages 完整消息列表（已按"清除上下文"分隔线裁剪过）
     * @param config 模型配置（contextWindow 为空用默认 32000）
     * @param systemPrompt 系统提示词（计入预算）
     * @param maxTokens 请求的输出上限（<=0 时用默认预留）
     * @return 裁剪结果
     */
    public BudgetResult applyBudget(List<NewBotMessage> messages, ModelConfig config,
                                    String systemPrompt, int maxTokens) {
        BudgetResult result = new BudgetResult();
        result.contextWindow = config != null && config.getContextWindow() != null && config.getContextWindow() > 0
                ? config.getContextWindow() : DEFAULT_CONTEXT_WINDOW;
        int outputReserve = maxTokens > 0 ? maxTokens : DEFAULT_OUTPUT_RESERVE;
        int systemTokens = estimateTokens(systemPrompt);
        // 可用历史预算
        int budget = result.contextWindow - outputReserve - systemTokens;
        if (budget < 1000) budget = 1000; // 兜底：至少保留一点历史空间

        List<NewBotMessage> all = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        // 过滤 system（后端统一注入，不占历史名额）与空 assistant（与原逻辑一致）
        List<NewBotMessage> effective = new ArrayList<>();
        for (NewBotMessage m : all) {
            if (m == null || "system".equals(m.getRole())) continue;
            if ("assistant".equals(m.getRole())
                    && (m.getContent() == null || m.getContent().trim().isEmpty())) continue;
            effective.add(m);
        }
        if (effective.isEmpty()) {
            result.messages = effective;
            result.estimatedPromptTokens = systemTokens;
            return result;
        }

        // 当前问题（最后一条）必须保留
        NewBotMessage last = effective.get(effective.size() - 1);
        int lastTokens = estimateMessageTokens(last);
        if (lastTokens > budget) {
            // 当前输入本身超限：不静默截断用户关键内容，明确标记由上层提示
            result.messages = effective;
            result.inputOverLimit = true;
            result.estimatedPromptTokens = systemTokens + lastTokens;
            return result;
        }

        // 从最新往旧累加完整轮次，超出预算即停止（保持连续后缀，角色顺序不被破坏）
        int used = lastTokens;
        int keepFrom = effective.size() - 1;
        for (int i = effective.size() - 2; i >= 0; i--) {
            int t = estimateMessageTokens(effective.get(i));
            if (used + t > budget) break;
            used += t;
            keepFrom = i;
        }
        // 保证裁剪边界不断开 user/assistant 配对：若保留下来的首条是 assistant，再往前补一条 user（若预算允许则已在上面包含）
        if (keepFrom > 0 && "assistant".equals(effective.get(keepFrom).getRole())
                && "user".equals(effective.get(keepFrom - 1).getRole())) {
            // 上一轮的 user 未纳入会导致 assistant 缺少问题上下文：丢弃该 assistant，从下一轮开始保留
            keepFrom++;
        }
        result.droppedCount = keepFrom;
        result.messages = effective.subList(keepFrom, effective.size());
        result.estimatedPromptTokens = systemTokens + used;
        if (result.droppedCount > 0) {
            log.info("上下文预算裁剪: 模型容量={}, 裁掉最早{}条, 保留{}条, 预计输入≈{} tokens",
                    result.contextWindow, result.droppedCount, result.messages.size(), result.estimatedPromptTokens);
        }
        return result;
    }

    /**
     * 历史附件内容截断：仅当前轮（最后一条消息）的附件全文注入，
     * 历史轮次附件截断为前 HISTORY_ATTACHMENT_CLIP 字符并明确标注，减少每轮重复塞入全文。
     * @param text 附件解析文本
     * @param isCurrentTurn 是否当前轮消息
     * @return 处理后的附件文本（历史轮次截断并标注省略范围）
     */
    public String clipAttachmentText(String text, boolean isCurrentTurn) {
        if (text == null) return null;
        if (isCurrentTurn || text.length() <= HISTORY_ATTACHMENT_CLIP) {
            return text;
        }
        return text.substring(0, HISTORY_ATTACHMENT_CLIP)
                + "\n（附件内容过长，此处仅保留前 " + HISTORY_ATTACHMENT_CLIP + " 字符，完整内容已在首次提问时提供）";
    }
}
