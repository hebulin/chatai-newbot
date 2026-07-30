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
            return storedHash.equals(legacySha256(rawPassword));
        }
        try {
            return BCrypt.checkpw(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // 存储的哈希格式非法时视为校验失败
            return false;
        }
    }

    /**
     * 旧版 SHA-256 密码哈希（加盐）——仅用于校验存量旧数据，登录通过后由调用方透明升级为 BCrypt
     * @param password 明文密码
     * @return 哈希后的十六进制字符串
     */
    static String legacySha256(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("chatai_salt_" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }
}
