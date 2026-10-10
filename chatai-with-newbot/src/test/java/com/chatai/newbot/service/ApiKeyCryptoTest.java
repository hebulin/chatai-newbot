package com.chatai.newbot.service;

import com.chatai.newbot.support.IsolatedDataDirectory;
import org.junit.jupiter.api.parallel.Isolated;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiKeyCrypto 单元测试：AES-256-GCM 加解密往返、ENC: 前缀识别、存量明文兼容
 * <p>
 * 注意：ApiKeyCrypto 的密钥文件路径基于 user.dir，测试前将 user.dir 重定向到临时目录，
 * 避免在模块目录下生成 data/apikey.secret 测试残留（密钥为进程内静态单例，首次加载后固定）。
 */
@Isolated
class ApiKeyCryptoTest {

    @TempDir
    static Path tempDir;

    private static IsolatedDataDirectory dataDirectory;

    /** 隔离目录与密钥缓存，避免继承其他测试类的静态密钥。 */
    @BeforeAll
    static void redirectUserDir() throws Exception {
        dataDirectory = new IsolatedDataDirectory(tempDir);
    }

    /** 还原测试前全局状态，不写入开发环境的数据目录。 */
    @AfterAll
    static void restoreUserDir() {
        if (dataDirectory != null) dataDirectory.close();
    }

    @Test
    void encrypt_应生成ENC前缀密文并可解密还原() {
        String plain = "sk-1234567890abcdef";
        String encrypted = ApiKeyCrypto.encrypt(plain);
        assertTrue(encrypted.startsWith("ENC:"), "密文应以 ENC: 前缀标识");
        assertEquals(plain, ApiKeyCrypto.decrypt(encrypted), "解密后应还原明文");
    }

    @Test
    void encrypt_相同明文每次密文不同() {
        // GCM 每次使用随机 IV，同一明文两次加密结果应不同
        String plain = "sk-same-key";
        assertNotEquals(ApiKeyCrypto.encrypt(plain), ApiKeyCrypto.encrypt(plain));
    }

    @Test
    void encrypt_已加密内容不重复加密() {
        String encrypted = ApiKeyCrypto.encrypt("sk-abc");
        assertSame(encrypted, ApiKeyCrypto.encrypt(encrypted), "ENC: 前缀内容应原样返回");
    }

    @Test
    void encrypt_空值原样返回() {
        assertNull(ApiKeyCrypto.encrypt(null));
        assertEquals("", ApiKeyCrypto.encrypt(""));
    }

    @Test
    void decrypt_存量明文原样返回() {
        // 无 ENC: 前缀的历史明文 Key 应原样返回（兼容旧数据）
        assertEquals("sk-plain-legacy", ApiKeyCrypto.decrypt("sk-plain-legacy"));
        assertNull(ApiKeyCrypto.decrypt(null));
    }

    @Test
    void encrypt_支持中文与特殊字符() {
        String plain = "密钥-テスト-!@#$%^&*()_+=/\\";
        assertEquals(plain, ApiKeyCrypto.decrypt(ApiKeyCrypto.encrypt(plain)));
    }

    @Test
    void isEncrypted_正确识别密文格式() {
        assertTrue(ApiKeyCrypto.isEncrypted(ApiKeyCrypto.encrypt("sk-xyz")));
        assertFalse(ApiKeyCrypto.isEncrypted("sk-plain"));
        assertFalse(ApiKeyCrypto.isEncrypted(null));
        assertFalse(ApiKeyCrypto.isEncrypted(""));
    }

    /** 从真正空白的目录验证首次密钥落盘和匹配，禁止使用恒真的兜底断言。 */
    @Test
    void loadKey_首次加密自动生成密钥文件() throws Exception {
        Path freshDirectory = tempDir.resolve("first-encryption");
        try (var ignored = new IsolatedDataDirectory(freshDirectory)) {
            Path secret = freshDirectory.resolve("data/apikey.secret");
            assertFalse(Files.exists(secret));
            String encrypted = ApiKeyCrypto.encrypt("trigger-key-generation");
            assertTrue(Files.isRegularFile(secret), "首次使用应在当前数据目录生成密钥文件");
            assertTrue(ApiKeyCrypto.canDecryptWith(encrypted, Files.readString(secret).trim()));
        }
    }
}
