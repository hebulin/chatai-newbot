package com.chatai.newbot.controller;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证聊天接口在 MVC 真正启动异步响应后取消总时长限制。 */
class ChatStreamTimeoutTest {
    /** 保留默认异步超时配置给其他接口，仅聊天 SSE 覆盖为无总时长限制。 */
    @Test
    void chat_持续输出不继承全局两分钟截止时间() throws Exception {
        StorageManager storage = mock(StorageManager.class);
        UnifiedChatService chat = mock(UnifiedChatService.class);
        ModelConfig model = new ModelConfig();
        model.setEnabled(true);
        when(storage.getModelConfigById("model")).thenReturn(model);
        when(chat.chat(any(), eq("model"), any())).thenReturn(
                Flux.just("[DONE]").delayElements(Duration.ofMillis(100)));
        var controller = new ChatController(chat, storage, mock(ChatHistoryService.class),
                mock(RateLimitService.class), mock(BudgetReservationService.class),
                mock(SvgAvatarService.class), mock(ObservabilityService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setAsyncRequestTimeout(120000).build();
        User admin = new User();
        admin.setId("admin");
        admin.setRole("admin");
        var result = mvc.perform(post("/api/chat").requestAttr("currentUser", admin)
                        .contentType("application/json").content("{\"modelConfigId\":\"model\",\"messages\":[]}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertEquals(0L, result.getRequest().getAsyncContext().getTimeout());
        result.getAsyncResult(2000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
