package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BudgetReservationService 单元测试：验证尚未落库的并发请求也占用配额。 */
class BudgetReservationServiceTest {

    /** 模型空值继承动态全局配置；显式 0 和正整数都独立覆盖，清空后再次继承。 */
    @Test
    void reserve_全局继承和模型覆盖优先级() {
        StorageManager storage = mock(StorageManager.class);
        when(storage.getSetting(OutputTokenPolicy.GLOBAL_SETTING_KEY)).thenReturn("8192");
        BudgetReservationService service = new BudgetReservationService(storage);
        User user = new User();
        user.setId("inherit");
        ModelConfig model = new ModelConfig();
        ChatRequest inherited = new ChatRequest();
        assertTrue(service.reserve(user, inherited, model).allowed());
        assertEquals(8192, inherited.getMax_tokens());
        model.setMaxOutputTokens(65536);
        assertEquals(65536, OutputTokenPolicy.resolve(new ChatRequest(), model, "8192"));
        model.setMaxOutputTokens(0);
        assertEquals(0, OutputTokenPolicy.resolve(new ChatRequest(), model, "8192"));
        model.setMaxOutputTokens(4096);
        assertEquals(4096, OutputTokenPolicy.resolve(new ChatRequest(), model, "0"));
        model.setMaxOutputTokens(null);
        assertEquals(0, OutputTokenPolicy.resolve(new ChatRequest(), model, "0"));
        assertEquals(16384, OutputTokenPolicy.resolve(new ChatRequest(), model, null));
        assertEquals(16384, OutputTokenPolicy.globalLimit("broken"));
    }

    /** 未设置、显式缩小及配置长输出都应采用统一策略，不再被 6000 或 32000 截断。 */
    @Test
    void reserve_使用模型输出配置且按完整预算占用配额() {
        StorageManager storage = mock(StorageManager.class);
        BudgetReservationService service = new BudgetReservationService(storage);
        User user = new User();
        user.setId("long-output");
        ModelConfig model = new ModelConfig();
        ChatRequest automatic = new ChatRequest();
        assertEquals(0, automatic.getMax_tokens());
        assertTrue(service.reserve(user, automatic, model).allowed());
        assertEquals(16384, automatic.getMax_tokens());

        model.setMaxOutputTokens(65536);
        ChatRequest longRequest = new ChatRequest();
        assertTrue(service.reserve(user, longRequest, model).allowed());
        assertEquals(65536, longRequest.getMax_tokens());
        ChatRequest shortRequest = new ChatRequest();
        shortRequest.setMax_tokens(1000);
        assertEquals(1000, OutputTokenPolicy.resolve(shortRequest, model));
        shortRequest.setMax_tokens(Integer.MAX_VALUE);
        assertEquals(65536, OutputTokenPolicy.resolve(shortRequest, model));

        User limited = new User();
        limited.setId("limited");
        limited.setDailyLimitType("token");
        limited.setDailyLimitValue(65536);
        assertFalse(service.reserve(limited, new ChatRequest(), model).allowed());
        model.setMaxOutputTokens(0);
        limited.setDailyLimitValue(10000);
        ChatRequest unlimited = new ChatRequest();
        assertTrue(service.reserve(user, unlimited, model).allowed());
        assertEquals(0, unlimited.getMax_tokens());
        assertFalse(service.reserve(limited, new ChatRequest(), model).allowed());
    }

    /** 首个请求预占后第二个请求应被拒绝，释放预占后应恢复可用。 */
    @Test
    void reserve_并发请求不会同时越过次数上限() {
        StorageManager storage = mock(StorageManager.class);
        when(storage.getDailyChatLimit()).thenReturn(1);
        BudgetReservationService service = new BudgetReservationService(storage);
        User user = new User();
        user.setId("u1");
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of());
        ModelConfig model = new ModelConfig();

        BudgetReservationService.ReservationResult first = service.reserve(user, request, model);
        BudgetReservationService.ReservationResult second = service.reserve(user, new ChatRequest(), model);

        assertTrue(first.allowed());
        assertFalse(second.allowed());
        service.release(first.requestId());
        assertTrue(service.reserve(user, new ChatRequest(), model).allowed());
    }
}
