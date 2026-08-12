package com.chatai.newbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoginAttemptService 单元测试：失败计数、账户/IP 双维度锁定、成功重置
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void 初始状态未锁定() {
        assertEquals(0, service.getLockRemainSeconds("alice", "1.2.3.4"));
    }

    @Test
    void 失败4次不锁定_第5次触发账户锁定() {
        for (int i = 0; i < 4; i++) {
            service.onFailure("alice", "1.2.3.4");
        }
        assertEquals(0, service.getLockRemainSeconds("alice", "1.2.3.4"), "4 次失败不应锁定");

        service.onFailure("alice", "1.2.3.4");
        long remain = service.getLockRemainSeconds("alice", "1.2.3.4");
        assertTrue(remain > 0, "第 5 次失败应触发锁定");
        assertTrue(remain <= 600, "账户维度锁定时长应不超过 10 分钟");
    }

    @Test
    void 账户锁定不影响同IP其他账户() {
        for (int i = 0; i < 5; i++) {
            service.onFailure("alice", "1.2.3.4");
        }
        assertTrue(service.getLockRemainSeconds("alice", "1.2.3.4") > 0);
        // 同 IP 仅 5 次失败，未达 IP 维度阈值（20 次），其他账户不受影响
        assertEquals(0, service.getLockRemainSeconds("bob", "1.2.3.4"));
    }

    @Test
    void 账户锁定不影响其他IP同账户() {
        for (int i = 0; i < 5; i++) {
            service.onFailure("alice", "1.2.3.4");
        }
        assertEquals(0, service.getLockRemainSeconds("alice", "5.6.7.8"),
                "锁定按 用户名+IP 维度，换 IP 后计数独立");
    }

    @Test
    void 同IP撞库多个账户_20次失败触发IP锁定() {
        // 每个用户名只失败 4 次（不触发账户锁定），共 20 次触发 IP 维度锁定
        for (int u = 0; u < 5; u++) {
            for (int i = 0; i < 4; i++) {
                service.onFailure("user" + u, "9.9.9.9");
            }
        }
        long remain = service.getLockRemainSeconds("newUser", "9.9.9.9");
        assertTrue(remain > 0, "IP 累计 20 次失败应锁定该 IP 下所有账户");
        assertTrue(remain <= 1800, "IP 维度锁定时长应不超过 30 分钟");
    }

    @Test
    void 登录成功清空失败计数() {
        for (int i = 0; i < 4; i++) {
            service.onFailure("alice", "1.2.3.4");
        }
        service.onSuccess("alice", "1.2.3.4");
        // 计数已清零，再失败 4 次仍不应锁定
        for (int i = 0; i < 4; i++) {
            service.onFailure("alice", "1.2.3.4");
        }
        assertEquals(0, service.getLockRemainSeconds("alice", "1.2.3.4"));
    }

    @Test
    void 用户名大小写不敏感() {
        for (int i = 0; i < 5; i++) {
            service.onFailure("Alice", "1.2.3.4");
        }
        assertTrue(service.getLockRemainSeconds("ALICE", "1.2.3.4") > 0,
                "同一用户名不同大小写应共享失败计数");
    }
}
