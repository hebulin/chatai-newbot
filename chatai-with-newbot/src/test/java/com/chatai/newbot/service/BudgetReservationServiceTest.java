package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BudgetReservationService 单元测试：验证尚未落库的并发请求也占用配额。 */
class BudgetReservationServiceTest {

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
