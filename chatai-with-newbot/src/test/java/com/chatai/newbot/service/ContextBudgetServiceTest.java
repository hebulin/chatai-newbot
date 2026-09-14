package com.chatai.newbot.service;

import com.chatai.newbot.model.NewBotMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证总上下文容量与输出空间的联合预算和完整轮次裁剪。 */
class ContextBudgetServiceTest {
    private final ContextBudgetService service = new ContextBudgetService();

    /** 当前问题优先，输出仅作上限；32K 模型必须给回答留下真实空间。 */
    @Test
    void applyBudget_输出空间从总容量扣除且不丢当前问题() {
        var messages = List.of(message("user", "旧问题"), message("assistant", "旧回答".repeat(1000)),
                message("user", "a".repeat(68000)));
        var result = service.applyBudget(messages, "系统", 32000, 16384);
        assertFalse(result.inputOverLimit);
        assertEquals(2, result.droppedCount);
        assertEquals(messages.getLast(), result.messages.getLast());
        assertTrue(result.estimatedPromptTokens < 32000);
        assertTrue(result.inputBudget < 20000);
    }

    /** 大容量模型保留超过旧 32K 输入阈值的内容，仅在真实容量不足时拒绝。 */
    @Test
    void applyBudget_大容量模型可处理超过旧预算的输入() {
        var messages = List.of(message("user", "a".repeat(160000)));
        assertFalse(service.applyBudget(messages, "系统", 128000, 16384).inputOverLimit);
        assertTrue(service.applyBudget(messages, "系统", 32000, 0).inputOverLimit);
    }

    /** 构造指定角色的文本消息。 */
    private NewBotMessage message(String role, String content) {
        NewBotMessage message = new NewBotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /** 中文与英文估算保持安全系数，空文本为零。 */
    @Test
    void estimateTokens_保留保守估算() {
        assertEquals(115, service.estimateTokens("汉".repeat(100)));
        assertEquals(115, service.estimateTokens("a".repeat(400)));
        assertEquals(0, service.estimateTokens(null));
        assertEquals(0, service.estimateTokens(""));
    }

    /** 输入整理保留最近连续轮次，不以孤立的回答开头。 */
    @Test
    void applyBudget_保留最近完整轮次() {
        List<NewBotMessage> messages = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            messages.add(message("user", "问题".repeat(25)));
            messages.add(message("assistant", "回答".repeat(100)));
        }
        messages.add(message("user", "当前问题"));
        var result = service.applyBudget(messages, "系统", 2000);
        assertFalse(result.inputOverLimit);
        assertTrue(result.droppedCount > 0);
        assertEquals("user", result.messages.getFirst().getRole());
        assertEquals("当前问题", result.messages.getLast().getContent());
        assertTrue(result.estimatedPromptTokens <= result.inputBudget);
    }

    /** 超长当前输入明确拒绝，不静默改写用户文本。 */
    @Test
    void applyBudget_当前输入超限保持原文() {
        var messages = List.of(message("user", "字".repeat(50000)));
        var result = service.applyBudget(messages, "系统");
        assertTrue(result.inputOverLimit);
        assertEquals(messages, result.messages);
    }

    /** 系统提示耗尽预算时即使消息列表为空也不能误判可用。 */
    @Test
    void applyBudget_系统提示词超限() {
        assertTrue(service.applyBudget(List.of(), "字".repeat(5000), 4000).inputOverLimit);
        assertTrue(service.applyBudget(List.of(message("user", "当前问题")), "字".repeat(5000), 4000).inputOverLimit);
    }

    /** 常规对话使用内部输入预算，不再依赖模型配置字段。 */
    @Test
    void applyBudget_未超预算原样保留() {
        var messages = List.of(message("user", "你好"), message("assistant", "你好"));
        var result = service.applyBudget(messages, "系统");
        assertEquals(0, result.droppedCount);
        assertEquals(messages, result.messages);
        assertEquals(ContextBudgetService.DEFAULT_INPUT_BUDGET, result.inputBudget);
    }

    /** 历史附件仍缩略，当前附件保留全文。 */
    @Test
    void clipAttachmentText_仅缩略历史附件() {
        String content = "附件内容".repeat(500);
        assertEquals(content, service.clipAttachmentText(content, true));
        assertTrue(service.clipAttachmentText(content, false).contains("附件内容过长"));
        assertTrue(service.clipAttachmentText(content, false).length() < content.length());
        assertEquals("短附件", service.clipAttachmentText("短附件", false));
    }
}
