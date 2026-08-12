package com.chatai.newbot.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordHasher 单元测试：BCrypt 哈希/校验 + 旧版 SHA-256 兼容
 */
class PasswordHasherTest {

    @Test
    void hash_应生成BCrypt格式哈希() {
        String hash = PasswordHasher.hash("test123");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希应以 $2 开头");
    }

    @Test
    void hash_相同密码每次生成不同哈希() {
        // BCrypt 每次使用随机盐，同一密码两次哈希结果应不同
        String h1 = PasswordHasher.hash("same-password");
        String h2 = PasswordHasher.hash("same-password");
        assertNotEquals(h1, h2);
    }

    @Test
    void matches_正确密码校验通过() {
        String hash = PasswordHasher.hash("myP@ssw0rd");
        assertTrue(PasswordHasher.matches("myP@ssw0rd", hash));
    }

    @Test
    void matches_错误密码校验失败() {
        String hash = PasswordHasher.hash("myP@ssw0rd");
        assertFalse(PasswordHasher.matches("wrongPassword", hash));
    }

    @Test
    void matches_兼容旧版SHA256哈希() {
        // 模拟存量用户：密码以旧版 SHA-256 格式存储
        String legacyHash = PasswordHasher.legacySha256("oldUserPwd");
        assertTrue(PasswordHasher.matches("oldUserPwd", legacyHash));
        assertFalse(PasswordHasher.matches("wrongPwd", legacyHash));
    }

    @Test
    void matches_空值与非法哈希不抛异常且返回false() {
        assertFalse(PasswordHasher.matches(null, "$2a$10$xxx"));
        assertFalse(PasswordHasher.matches("pwd", null));
        assertFalse(PasswordHasher.matches("pwd", ""));
        // $2 开头但格式非法的哈希应静默返回 false 而非抛异常
        assertFalse(PasswordHasher.matches("pwd", "$2a$invalid"));
    }

    @Test
    void isLegacyHash_识别新旧格式() {
        assertTrue(PasswordHasher.isLegacyHash(PasswordHasher.legacySha256("x")));
        assertFalse(PasswordHasher.isLegacyHash(PasswordHasher.hash("x")));
        assertFalse(PasswordHasher.isLegacyHash(null));
    }
}
