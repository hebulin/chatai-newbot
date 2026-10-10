package com.chatai.newbot.support;

import com.chatai.newbot.service.ApiKeyCrypto;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试专用数据作用域：一起隔离工作目录与进程密钥缓存，使用者须标记 JUnit @Isolated。 */
public final class IsolatedDataDirectory implements AutoCloseable {
    private final String originalDirectory;
    private final Object originalKey;
    private boolean closed;

    /** 在变更全局状态前创建测试目录，进入后由真实加密代码加载或生成该目录的密钥。 */
    public IsolatedDataDirectory(Path directory) throws IOException {
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(absoluteDirectory);
        synchronized (ApiKeyCrypto.class) {
            originalDirectory = System.getProperty("user.dir");
            originalKey = ReflectionTestUtils.getField(ApiKeyCrypto.class, "key");
            System.setProperty("user.dir", absoluteDirectory.toString());
            ReflectionTestUtils.setField(ApiKeyCrypto.class, "key", null);
        }
    }

    /** 原样恢复进入前的目录和缓存；不读取、不生成、不覆盖真实开发目录中的密钥。 */
    @Override
    public void close() {
        synchronized (ApiKeyCrypto.class) {
            if (closed) return;
            if (originalDirectory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", originalDirectory);
            ReflectionTestUtils.setField(ApiKeyCrypto.class, "key", originalKey);
            closed = true;
        }
    }
}
