package com.chatai.newbot.service;

import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * 密码哈希工具：BCrypt（新版）+ SHA-256（旧数据兼容）
 * 新密码一律使用 BCrypt 存储；旧版 SHA-256 哈希在登录校验通过后由调用方透明升级为 BCrypt。
 */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    /**
     * 生成 BCrypt 密码哈希
     * @param rawPassword 明文密码
     * @return BCrypt 哈希字符串（$2a$ 开头）
     */
    public static String hash(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(10));
    }

    /**
     * 判断存储的哈希是否为旧版 SHA-256 格式（非 $2 开头即视为旧版）
     * @param storedHash 存储的密码哈希
     * @return true=旧版 SHA-256
     */
    public static boolean isLegacyHash(String storedHash) {
        return storedHash != null && !storedHash.startsWith("$2");
    }

    /**
     * 校验密码，兼容 BCrypt 与旧版 SHA-256 两种存储格式
     * @param rawPassword 明文密码
     * @param storedHash 存储的密码哈希
     * @return true=密码正确
     */
    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        if (isLegacyHash(storedHash)) {
            return storedHash.equals(JsonFileStorageService.hashPassword(rawPassword));
        }
        try {
            return BCrypt.checkpw(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // 存储的哈希格式非法时视为校验失败
            return false;
        }
    }
}
