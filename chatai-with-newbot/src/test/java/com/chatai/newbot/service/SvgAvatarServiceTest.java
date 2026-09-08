package com.chatai.newbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SvgAvatarService 单元测试：覆盖安全 SVG 与脚本能力拦截。 */
class SvgAvatarServiceTest {

    /** 只含基础矢量元素的 SVG 应原样通过。 */
    @Test
    void sanitize_允许基础矢量图形() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle cx=\"10\" cy=\"10\" r=\"8\"/></svg>";
        assertEquals(svg, new SvgAvatarService().sanitize(svg));
    }

    /** 事件处理器或脚本元素必须被拒绝。 */
    @Test
    void sanitize_拒绝可执行内容() {
        SvgAvatarService service = new SvgAvatarService();
        assertThrows(IllegalArgumentException.class,
                () -> service.sanitize("<svg onload=\"alert(1)\"></svg>"));
        assertThrows(IllegalArgumentException.class,
                () -> service.sanitize("<svg><script>alert(1)</script></svg>"));
    }
}
