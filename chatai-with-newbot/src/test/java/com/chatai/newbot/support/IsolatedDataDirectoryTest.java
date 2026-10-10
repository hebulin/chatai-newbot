package com.chatai.newbot.support;

import com.chatai.newbot.service.ApiKeyCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 回归 CI 的共享 JVM 场景：一个测试已加载密钥后，另一个测试必须使用自己的磁盘密钥。 */
@Isolated
class IsolatedDataDirectoryTest {
    @TempDir Path directory;

    /** 切换全新/已有测试目录均不得沿用前一个目录缓存，退出后恢复原始缓存。 */
    @Test
    void switchingDirectoriesUsesTheirOwnKeysAndRestoresPreviousState() throws Exception {
        String originalDirectory = System.getProperty("user.dir");
        Object originalKey = ReflectionTestUtils.getField(ApiKeyCrypto.class, "key");
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        try (var firstScope = new IsolatedDataDirectory(first)) {
            String firstCipher = ApiKeyCrypto.encrypt("first-value");
            String firstKey = Files.readString(first.resolve("data/apikey.secret"));
            String secondCipher;
            try (var secondScope = new IsolatedDataDirectory(second)) {
                assertNull(ReflectionTestUtils.getField(ApiKeyCrypto.class, "key"));
                secondCipher = ApiKeyCrypto.encrypt("second-value");
                String secondKey = Files.readString(second.resolve("data/apikey.secret"));
                assertNotEquals(firstKey, secondKey);
                assertTrue(ApiKeyCrypto.canDecryptWith(secondCipher, secondKey));
                assertFalse(ApiKeyCrypto.canDecryptWith(secondCipher, firstKey));
            }
            assertEquals(first.toString(), System.getProperty("user.dir"));
            assertEquals("first-value", ApiKeyCrypto.decrypt(firstCipher));
            try (var reopened = new IsolatedDataDirectory(second)) {
                assertEquals("second-value", ApiKeyCrypto.decrypt(secondCipher));
            }
            assertEquals("first-value", ApiKeyCrypto.decrypt(firstCipher));
        }
        assertEquals(originalDirectory, System.getProperty("user.dir"));
        assertSame(originalKey, ReflectionTestUtils.getField(ApiKeyCrypto.class, "key"));
    }

    /** 测试执行途中异常也必须恢复上一层状态，不污染后续测试。 */
    @Test
    void exceptionStillRestoresDirectoryAndCachedKey() throws Exception {
        Path first = directory.resolve("first");
        try (var firstScope = new IsolatedDataDirectory(first)) {
            String encrypted = ApiKeyCrypto.encrypt("keep-me");
            assertThrows(IOException.class, () -> {
                try (var failingScope = new IsolatedDataDirectory(directory.resolve("failed"))) {
                    ApiKeyCrypto.encrypt("other-value");
                    throw new IOException("模拟测试失败");
                }
            });
            assertEquals(first.toString(), System.getProperty("user.dir"));
            assertEquals("keep-me", ApiKeyCrypto.decrypt(encrypted));
        }
    }

    /** 目录创建失败时尚未修改全局状态，无须依赖关闭回调补偿。 */
    @Test
    void invalidDirectoryDoesNotChangeGlobalState() throws Exception {
        String originalDirectory = System.getProperty("user.dir");
        Object originalKey = ReflectionTestUtils.getField(ApiKeyCrypto.class, "key");
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "fixture");
        assertThrows(IOException.class, () -> new IsolatedDataDirectory(file));
        assertEquals(originalDirectory, System.getProperty("user.dir"));
        assertSame(originalKey, ReflectionTestUtils.getField(ApiKeyCrypto.class, "key"));
    }
}
