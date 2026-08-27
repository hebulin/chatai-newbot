package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * API Key 对称加密工具（AES-256-GCM）
 * <p>
 * 目标：厂商 API Key 不再明文落盘（SQLite/JSON），数据文件被拖库后无法直接读取 Key。
 * <ul>
 *   <li>密钥：首次使用自动生成 256 位随机密钥，保存在 data/apikey.secret（需与数据文件一同备份）</li>
 *   <li>密文格式：ENC:base64(iv + ciphertext)，每次加密使用随机 IV</li>
 *   <li>兼容性：解密时遇到无 ENC: 前缀的存量明文原样返回，下次保存时自动升级为密文</li>
 * </ul>
 */
public final class ApiKeyCrypto {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyCrypto.class);
    /** 密文前缀，用于区分密文与存量明文 */
    private static final String PREFIX = "ENC:";
    private static final String KEY_FILE = "apikey.secret";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 进程内单例密钥（懒加载，双检锁） */
    private static volatile SecretKey key;

    private ApiKeyCrypto() {
    }

    /**
     * 加密 API Key
     * @param plain 明文 Key；null/空串/已是密文时原样返回
     * @return ENC: 前缀的密文
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 失败时快速报错（fail-closed），避免静默将明文 Key 落盘造成拖库泄露
            log.error("API Key 加密失败，为防止明文存储已中止本次保存", e);
            throw new IllegalStateException("API Key 加密失败，未保存", e);
        }
    }

    /**
     * 解密 API Key
     * @param stored 存储值；无 ENC: 前缀的存量明文原样返回
     * @return 明文 Key
     */
    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plain = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密钥文件丢失/损坏时解密失败，返回原值并告警（该 Key 需重新录入）
            log.error("API Key 解密失败，请检查 data/apikey.secret 是否丢失或被篡改", e);
            return stored;
        }
    }

    /**
     * 判断存储值是否为密文格式
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * 用指定的密钥内容（Base64）试解密给定密文（备份恢复前的密钥匹配校验用，
     * 不影响进程内当前密钥）。
     * @param encrypted ENC: 前缀密文
     * @param keyContent Base64 编码的密钥文件内容
     * @return true=该密钥可以解密此密文
     */
    public static boolean canDecryptWith(String encrypted, String keyContent) {
        if (!isEncrypted(encrypted) || keyContent == null || keyContent.isEmpty()) {
            return false;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyContent.trim());
            SecretKey trialKey = new SecretKeySpec(keyBytes, "AES");
            byte[] combined = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, trialKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 加载密钥：优先读取 data/apikey.secret，不存在则生成随机密钥并落盘
     */
    private static SecretKey loadKey() throws Exception {
        if (key == null) {
            synchronized (ApiKeyCrypto.class) {
                if (key == null) {
                    Path keyPath = Paths.get(System.getProperty("user.dir"), "data", KEY_FILE);
                    if (Files.exists(keyPath)) {
                        byte[] keyBytes = Base64.getDecoder().decode(
                                Files.readString(keyPath, StandardCharsets.UTF_8).trim());
                        key = new SecretKeySpec(keyBytes, "AES");
                    } else {
                        KeyGenerator generator = KeyGenerator.getInstance("AES");
                        generator.init(256, RANDOM);
                        SecretKey newKey = generator.generateKey();
                        Files.createDirectories(keyPath.getParent());
                        Files.writeString(keyPath, Base64.getEncoder().encodeToString(newKey.getEncoded()),
                                StandardCharsets.UTF_8);
                        // 收紧密钥文件权限为 600（仅属主可读写）：多用户 Linux 服务器上防止其他账户读取。
                        // Windows 不支持 POSIX 权限视图，抛异常时忽略（NTFS ACL 由部署环境自行管控）
                        try {
                            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                            Files.setPosixFilePermissions(keyPath, perms);
                        } catch (UnsupportedOperationException | java.io.IOException ignore) {
                            // 非 POSIX 文件系统（Windows）或权限设置失败，不影响功能
                        }
                        key = newKey;
                        log.info("已生成 API Key 加密密钥: {}（请与数据文件一同备份，丢失后已存 Key 无法解密）", keyPath);
                    }
                }
            }
        }
        return key;
    }
}
