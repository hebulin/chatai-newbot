package com.chatai.newbot.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 跨密钥恢复以及文件切换失败的真实数据库/文件一致性回归。 */
class BackupRecoveryRegressionTest {
    @TempDir Path dir;
    private String originalDir;
    private Object originalKey;
    private JdbcTemplate jdbc;
    private StorageManager storage;
    private BackupService backups;

    /** 所有数据库、密钥和文件均位于测试专用目录，保存进程缓存供结束时原样还原。 */
    @BeforeEach
    void setup() throws Exception {
        originalDir = System.getProperty("user.dir");
        originalKey = ReflectionTestUtils.getField(ApiKeyCrypto.class, "key");
        System.setProperty("user.dir", dir.toString());
        Files.createDirectories(dir.resolve("data/uploads/202610"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("data/chatai.db")));
        SqliteStorageService sqlite = new SqliteStorageService(jdbc);
        sqlite.init();
        storage = new StorageManager(sqlite);
        backups = new BackupService(jdbc, storage);
        writeState((byte) 1, "backup");
    }

    /** 还原进程目录和密钥缓存，不读取或替换开发环境密钥文件。 */
    @AfterEach
    void cleanup() {
        System.setProperty("user.dir", originalDir);
        ReflectionTestUtils.setField(ApiKeyCrypto.class, "key", originalKey);
    }

    /** 恢复其它密钥的备份后，不重启即可解密旧数据，新加密也必须使用恢复后的密钥。 */
    @Test
    void differentKeyRestoresCryptoAndAllResources() throws Exception {
        Map<String, Object> backup = backups.createBackup();
        String cipher = jdbc.queryForObject("SELECT password_enc FROM t_chat_share WHERE id='crypto'", String.class);
        writeState((byte) 2, "live");
        Files.writeString(dir.resolve("data/uploads/202610/new.txt"), "new");
        backups.restoreBackup(backups.resolveBackup((String) backup.get("name")));
        assertEquals("backup", ApiKeyCrypto.decrypt(cipher));
        assertEquals("backup", Files.readString(dir.resolve("data/uploads/202610/file.txt")));
        assertFalse(Files.exists(dir.resolve("data/uploads/202610/new.txt")));
        assertTrue(ApiKeyCrypto.canDecryptWith(ApiKeyCrypto.encrypt("new-write"), key((byte) 1)));
        assertFalse(backups.isRestoring());
    }

    /** 上传目录已经切换后密钥安装失败，数据库、原文件及缓存必须一起回到恢复前状态。 */
    @Test
    void failedFileSwitchRollsBackDatabaseFilesAndKey() throws Exception {
        Map<String, Object> backup = backups.createBackup();
        writeState((byte) 2, "live");
        String liveCipher = jdbc.queryForObject("SELECT password_enc FROM t_chat_share WHERE id='crypto'", String.class);
        BackupService failing = new BackupService(jdbc, storage) {
            /** 用可控文件系统故障模拟安装新密钥失败，回滚路径仍使用真实文件操作。 */
            @Override RestoreFileSwap prepareFileRestore(Path temp) throws IOException {
                return new RestoreFileSwap(temp, dir.resolve("data")) {
                    /** 仅新密钥安装失败，不干扰旧密钥归还。 */
                    @Override protected void move(Path source, Path target) throws IOException {
                        if (source.getFileName().toString().equals("new-apikey.secret")) throw new IOException("simulated disk error");
                        super.move(source, target);
                    }
                };
            }
        };
        assertThrows(Exception.class, () -> failing.restoreBackup(backups.resolveBackup((String) backup.get("name"))));
        assertEquals(liveCipher, jdbc.queryForObject("SELECT password_enc FROM t_chat_share WHERE id='crypto'", String.class));
        assertEquals("live", Files.readString(dir.resolve("data/uploads/202610/file.txt")));
        assertEquals("live", ApiKeyCrypto.decrypt(liveCipher));
        assertEquals(key((byte) 2), Files.readString(dir.resolve("data/apikey.secret")));
        assertFalse(failing.isRestoring());
    }

    /** 空资源快照恢复后不能残留恢复前的文件。 */
    @Test
    void emptyUploadsSnapshotRemovesLaterFiles() throws Exception {
        Files.delete(dir.resolve("data/uploads/202610/file.txt"));
        Map<String, Object> backup = backups.createBackup();
        Files.writeString(dir.resolve("data/uploads/202610/later.txt"), "later");
        backups.restoreBackup(backups.resolveBackup((String) backup.get("name")));
        try (var files = Files.walk(dir.resolve("data/uploads"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    /** 提交已成功但连接恢复自动提交失败时，必须维持维护状态，不能开放旧缓存。 */
    @Test
    void postCommitConnectionFailureKeepsMaintenanceClosed() throws Exception {
        Map<String, Object> backup = backups.createBackup();
        writeState((byte) 2, "live");
        javax.sql.DataSource source = jdbc.getDataSource();
        var faultySource = new org.springframework.jdbc.datasource.AbstractDataSource() {
            /** 只在导入事务成功提交后的连接清理阶段注入故障。 */
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                java.sql.Connection connection = source.getConnection();
                java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0]) && committed.get()) {
                                throw new java.sql.SQLException("simulated post-commit cleanup failure");
                            }
                            try {
                                Object result = method.invoke(connection, args);
                                if (method.getName().equals("commit")) committed.set(true);
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                        });
            }
            /** 测试数据源不使用额外凭据。 */
            @Override public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
                return getConnection();
            }
        };
        BackupService faulty = new BackupService(new JdbcTemplate(faultySource), storage);
        assertThrows(Exception.class, () -> faulty.restoreBackup(backups.resolveBackup((String) backup.get("name"))));
        assertTrue(faulty.isRestoring());
        assertEquals("backup", Files.readString(dir.resolve("data/uploads/202610/file.txt")));
        assertEquals("backup", ApiKeyCrypto.decrypt(jdbc.queryForObject("SELECT password_enc FROM t_chat_share WHERE id='crypto'", String.class)));
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new com.chatai.newbot.config.MaintenanceFilter(faulty).doFilter(
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/auth/register"), response,
                (req, res) -> fail("故障维护期间不得进入注册逻辑"));
        assertEquals(503, response.getStatus());
    }

    /** 构建同一密钥下的数据库密文和文件，用于验证完整恢复而不是单一数据表。 */
    private void writeState(byte seed, String value) throws Exception {
        Files.writeString(dir.resolve("data/apikey.secret"), key(seed));
        ApiKeyCrypto.reloadKey();
        jdbc.update("INSERT OR REPLACE INTO t_chat_share(id,chat_id,user_id,password_enc) VALUES('crypto','c','u',?)", ApiKeyCrypto.encrypt(value));
        Files.writeString(dir.resolve("data/uploads/202610/file.txt"), value);
    }

    /** 生成两个不同但格式合法的测试密钥。 */
    private String key(byte seed) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, seed);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
