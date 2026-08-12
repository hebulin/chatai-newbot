package com.chatai.newbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 计费币种配置校验测试。 */
class BillingSettingsServiceTest {

    /** 验证没有保存配置时默认使用 Token 与人民币。 */
    @Test
    void getConfig_无配置时默认Token与人民币() {
        StorageManager storage = mock(StorageManager.class);
        BillingSettingsService service = new BillingSettingsService(storage);
        Map<String, Object> config = service.getConfig();
        assertEquals("token", config.get("displayMode"));
        assertEquals("CNY", config.get("defaultCurrency"));
    }

    /** 验证新增币种不能使用零汇率。 */
    @Test
    void saveConfig_新增币种必须设置正汇率() {
        StorageManager storage = mock(StorageManager.class);
        when(storage.getSetting("billing_display_mode")).thenReturn("token");
        BillingSettingsService service = new BillingSettingsService(storage);
        Map<String, Object> invalid = Map.of(
                "displayMode", "currency",
                "defaultCurrency", "USD",
                "currencies", List.of(Map.of("code", "USD", "name", "美元", "symbol", "$", "rate", 0)));
        assertThrows(IllegalArgumentException.class, () -> service.saveConfig(invalid));
    }
}
