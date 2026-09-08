package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextBudgetService 长对话上下文预算管理测试。
 * 覆盖：中文长文本估算、超长单条消息明确拒绝、完整对话轮次保留、
 * 附件历史截断、不同模型容量与输出预留。
 */
class ContextBudgetServiceTest {

    private final ContextBudgetService service = new ContextBudgetService();

    /** 构造用户消息 */
    private NewBotMessage user(String content) {
        NewBotMessage m = new NewBotMessage();
        m.setRole("user");
        m.setContent(content);
        return m;
    }

    /** 构造助手消息 */
    private NewBotMessage assistant(String content) {
        NewBotMessage m = new NewBotMessage();
        m.setRole("assistant");
        m.setContent(content);
        return m;
    }

    /** 构造指定上下文容量的模型配置 */
    private ModelConfig modelWithWindow(Integer contextWindow) {
        ModelConfig config = new ModelConfig();
        config.setContextWindow(contextWindow);
        return config;
    }

    @Test
    void estimateTokens_中文按字估算且带安全系数() {
        // 100 个汉字 ≈ 100 * 1.15 = 115 tokens
        int tokens = service.estimateTokens("汉".repeat(100));
        assertEquals(115, tokens);
        // 400 个 ASCII 字符 ≈ 100 * 1.15 = 115 tokens
        int asciiTokens = service.estimateTokens("a".repeat(400));
        assertEquals(115, asciiTokens);
        // 空文本为 0
        assertEquals(0, service.estimateTokens(null));
        assertEquals(0, service.estimateTokens(""));
    }

    @Test
    void applyBudget_保留最近完整对话轮次_不破坏角色顺序() {
        // 小容量模型：3200 tokens，输出预留 1000，系统提示约几十，预算约 2000
        ModelConfig config = modelWithWindow(3200);
        List<NewBotMessage> messages = new ArrayList<>();
        // 10 轮历史对话，每轮 user 50 字 + assistant 200 字，最后一条为当前问题
        for (int i = 0; i < 10; i++) {
            messages.add(user("问题" + i + "：" + "内".repeat(50)));
            messages.add(assistant("回答" + i + "：" + "容".repeat(200)));
        }
        messages.add(user("当前问题"));
        ContextBudgetService.BudgetResult result = service.applyBudget(messages, config, "你是助手", 1000);
        assertFalse(result.inputOverLimit);
        assertTrue(result.droppedCount > 0, "长历史应被裁剪");
        // 保留的消息必须是连续的最近后缀，且最后一条是当前问题
        assertEquals("user", result.messages.get(result.messages.size() - 1).getRole());
        assertTrue(result.messages.get(result.messages.size() - 1).getContent().contains("当前问题"));
        // 保留部分不以 assistant 开头（不破坏轮次配对：若边界落在 assistant，应再退一步）
        if (result.messages.size() > 1) {
            assertNotEquals("assistant", result.messages.get(0).getRole(),
                    "裁剪边界不得留下缺少问题的孤立 assistant 消息");
        }
    }

    @Test
    void applyBudget_当前输入超限_明确标记不静默截断() {
        ModelConfig config = modelWithWindow(4000);
        List<NewBotMessage> messages = List.of(user("超长问题：" + "字".repeat(50000)));
        ContextBudgetService.BudgetResult result = service.applyBudget(messages, config, "系统", 1000);
        assertTrue(result.inputOverLimit, "当前输入本身超限必须明确标记，由上层报错而不是静默截断");
    }

    @Test
    void applyBudget_未超预算时原样保留() {
        ModelConfig config = modelWithWindow(null); // 默认 32000
        List<NewBotMessage> messages = List.of(user("你好"), assistant("你好！有什么可以帮你？"));
        ContextBudgetService.BudgetResult result = service.applyBudget(messages, config, "系统", 4096);
        assertEquals(0, result.droppedCount);
        assertEquals(2, result.messages.size());
        assertEquals(ContextBudgetService.DEFAULT_CONTEXT_WINDOW, result.contextWindow);
    }

    @Test
    void clipAttachmentText_历史附件截断当前轮全文() {
        String longText = "附件内容".repeat(500); // 2000 字
        // 当前轮：全文保留
        assertEquals(longText, service.clipAttachmentText(longText, true));
        // 历史轮次：截断并标注
        String clipped = service.clipAttachmentText(longText, false);
        assertTrue(clipped.length() < longText.length());
        assertTrue(clipped.contains("附件内容过长"), "截断后必须明确标注省略范围");
        // 短附件不截断
        String shortText = "短附件";
        assertEquals(shortText, service.clipAttachmentText(shortText, false));
    }

    @Test
    void applyBudget_输出预留纳入预算() {
        // 输出预留越大，历史预算越小
        ModelConfig config = modelWithWindow(5000);
        List<NewBotMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(user("问题" + i + "：" + "字".repeat(100)));
            messages.add(assistant("回答" + i + "：" + "字".repeat(300)));
        }
        ContextBudgetService.BudgetResult smallReserve = service.applyBudget(messages, config, "系统", 500);
        ContextBudgetService.BudgetResult largeReserve = service.applyBudget(messages, config, "系统", 4000);
        assertTrue(largeReserve.droppedCount >= smallReserve.droppedCount,
                "输出预留增大时历史预算应收紧（裁掉更多）");
    }
}
