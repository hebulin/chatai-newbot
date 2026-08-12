package com.chatai.newbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimitService 单元测试：滑动窗口限流的核心行为
 * （不限制/未超限放行计数/超限拒绝/不同用户独立计数）
 */
class RateLimitServiceTest {

    @Test
    void tryAcquire_不限制时始终放行() {
        RateLimitService svc = new RateLimitService();
        for (int i = 0; i < 100; i++) {
            assertTrue(svc.tryAcquire("u1", 0), "limit<=0 应不限制");
        }
        assertTrue(svc.tryAcquire("u1", -5), "负数上限应视为不限制");
    }

    @Test
    void tryAcquire_超过每分钟上限后拒绝() {
        RateLimitService svc = new RateLimitService();
        int limit = 3;
        for (int i = 0; i < limit; i++) {
            assertTrue(svc.tryAcquire("u1", limit), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(svc.tryAcquire("u1", limit), "超过上限应拒绝");
    }

    @Test
    void tryAcquire_不同用户独立计数() {
        RateLimitService svc = new RateLimitService();
        for (int i = 0; i < 5; i++) {
            svc.tryAcquire("u1", 5);
        }
        assertFalse(svc.tryAcquire("u1", 5), "u1 已达上限");
        assertTrue(svc.tryAcquire("u2", 5), "u2 不受 u1 计数影响");
    }

    @Test
    void tryAcquire_用户ID为空时放行() {
        RateLimitService svc = new RateLimitService();
        assertTrue(svc.tryAcquire(null, 1), "userId 为 null 应放行（无法限流）");
    }
}
