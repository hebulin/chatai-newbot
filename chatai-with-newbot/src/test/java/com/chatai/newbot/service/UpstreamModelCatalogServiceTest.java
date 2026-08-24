package com.chatai.newbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** UpstreamModelCatalogService 单元测试：覆盖常见 API 基础地址到模型目录的转换。 */
class UpstreamModelCatalogServiceTest {

    /** 版本化地址追加 models，普通基础地址追加 v1/models，完整地址保持不变。 */
    @Test
    void normalizeModelsEndpoint_兼容三种基础地址() {
        UpstreamModelCatalogService service = new UpstreamModelCatalogService();
        assertEquals("http://example.test/v1/models",
                service.normalizeModelsEndpoint("http://example.test/v1"));
        assertEquals("http://example.test/v1/models",
                service.normalizeModelsEndpoint("http://example.test"));
        assertEquals("http://example.test/v1/models",
                service.normalizeModelsEndpoint("http://example.test/v1/models"));
    }
}
