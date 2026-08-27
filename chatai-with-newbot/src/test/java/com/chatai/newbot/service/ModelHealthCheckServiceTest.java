package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型健康检查调度测试：总开关/间隔由后台设置驱动（t_setting 优先、yml 兜底），
 * 心跳到期判定不重复执行，仅检查开启"健康检查"开关的模型。
 */
class ModelHealthCheckServiceTest {

    private UnifiedChatService chatService;
    private StorageManager storageManager;
    private ModelHealthCheckService service;

    @BeforeEach
    void setUp() {
        chatService = mock(UnifiedChatService.class);
        storageManager = mock(StorageManager.class);
        service = new ModelHealthCheckService(chatService, storageManager);
        // yml 默认值：启用 / 360 分钟 / 首次立即（测试不等启动宽限）
        ReflectionTestUtils.setField(service, "enabledDefault", true);
        ReflectionTestUtils.setField(service, "intervalMinutesDefault", 360L);
        ReflectionTestUtils.setField(service, "initialDelayMinutesDefault", 0L);
    }

    /** 构造一个启用且配置完整的模型，healthCheckEnabled 可指定（null 模拟老数据缺省） */
    private ModelConfig newModel(String id, Boolean healthCheckEnabled) {
        ModelConfig m = new ModelConfig();
        m.setId(id);
        m.setModelId(id);
        m.setDisplayName(id);
        m.setApiUrl("https://example.test/v1");
        m.setApiKey("sk-x");
        m.setEnabled(true);
        m.setHealthCheckEnabled(healthCheckEnabled);
        return m;
    }

    @Test
    void tick_总开关关闭时不执行() {
        when(storageManager.getSetting("health_check_enabled")).thenReturn("false");
        service.tick();
        verify(chatService, never()).testConnection(anyString());
    }

    @Test
    void tick_间隔未到期不重复执行() {
        when(storageManager.getAllModelConfigs()).thenReturn(List.of(newModel("m1", true)));
        when(chatService.testConnection("m1")).thenReturn(Map.of("success", true));
        service.tick();
        service.tick();
        verify(chatService, times(1)).testConnection("m1");
    }

    @Test
    void tick_仅检查开启健康检查开关的模型() {
        when(storageManager.getAllModelConfigs()).thenReturn(List.of(
                newModel("on", true), newModel("off", false), newModel("legacy", null)));
        when(chatService.testConnection(anyString())).thenReturn(Map.of("success", true));
        service.tick();
        verify(chatService, times(1)).testConnection("on");
        // NULL 为老数据缺省语义，视为参与
        verify(chatService, times(1)).testConnection("legacy");
        verify(chatService, never()).testConnection("off");
    }

    @Test
    void tick_后台间隔设置优先于yml默认() {
        // 后台设置 1 分钟间隔：人为把上次执行时间拨到 2 分钟前，应到期再次执行
        when(storageManager.getSetting("health_check_interval_minutes")).thenReturn("1");
        when(storageManager.getAllModelConfigs()).thenReturn(List.of(newModel("m1", true)));
        when(chatService.testConnection("m1")).thenReturn(Map.of("success", true));
        service.tick();
        ReflectionTestUtils.setField(service, "lastRunAt", System.currentTimeMillis() - 120_000L);
        service.tick();
        verify(chatService, times(2)).testConnection("m1");
    }
}
