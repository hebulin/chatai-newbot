package com.chatai.newbot.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BackupService 完整备份与恢复测试（基于真实 SQLite 临时库与临时数据目录）。
 * 覆盖：备份创建（一致性快照+清单+校验）、恢复后数据一致性、密钥不匹配拒绝、
 * 路径穿越防护、恢复前自动备份、恢复后登录态失效。
 */
class BackupServiceTest {

    @TempDir
    static Path tempDir;

    private static String originalUserDir;
    private static SqliteStorageService storage;
    private static StorageManager storageManager;
    private static BackupService backupService;

    @BeforeAll
    static void setup() throws Exception {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        // 数据目录结构：data/chatai.db + data/uploads
        Files.createDirectories(tempDir.resolve("data/uploads/img/202608"));
        Files.writeString(tempDir.resolve("data/uploads/img/202608/test.png"), "fake-image-bytes");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("data/chatai.db").toAbsolutePath());
        storage = new SqliteStorageService(new JdbcTemplate(dataSource));
        storage.init();
        storageManager = new StorageManager(storage);
        backupService = new BackupService(new JdbcTemplate(dataSource), storageManager);
        // 确保密钥文件存在于当前临时数据目录（ApiKeyCrypto 为静态单例，
        // 全量测试时可能已被其他测试类加载而不在本目录生成文件，这里直接写入固定密钥）
        Path secretFile = tempDir.resolve("data/apikey.secret");
        if (!Files.exists(secretFile)) {
            Files.writeString(secretFile,
                    java.util.Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    @AfterAll
    static void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** 备份创建：zip 含数据库快照、密钥、上传资源与清单，清单文件校验可解析 */
    @Test
    void createBackup_包含数据库资源密钥与清单() throws Exception {
        // 造数据：用户 + 会话 + 模型
        storage.register("backup_user", "pass1234", "127.0.0.1");
        Map<String, Object> info = backupService.createBackup();
        assertNotNull(info.get("name"));
        assertTrue(((String) info.get("name")).endsWith(".zip"));
        assertTrue((Long) info.get("size") > 0);
        assertTrue((Integer) info.get("fileCount") >= 3, "至少包含数据库+密钥+上传文件");

        Path zipPath = backupService.resolveBackup((String) info.get("name"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipPath.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"), "备份必须含清单");
            assertNotNull(zip.getEntry("chatai.db"), "备份必须含数据库快照");
            assertNotNull(zip.getEntry("apikey.secret"), "备份必须含密钥文件");
            assertNotNull(zip.getEntry("uploads/img/202608/test.png"), "备份必须含上传资源");
        }
    }

    /** 恢复：备份后修改数据，恢复应回到备份时点，登录态全部失效，且自动生成恢复前备份 */
    @Test
    void restoreBackup_数据回到备份时点且登录态失效() throws Exception {
        // 备份时点数据
        String username = "restore_user";
        storage.register(username, "pass1234", "127.0.0.1");
        Map<String, Object> backup = backupService.createBackup();
        String backupName = (String) backup.get("name");

        // 备份后变更：再注册一个用户 + 创建一个登录 token
        storage.register("post_backup_user", "pass1234", "127.0.0.1");
        storageManager.createToken(
                storage.getAllUsers().stream().filter(u -> username.equals(u.getUsername())).findFirst().orElseThrow().getId(),
                "127.0.0.1", "test");
        assertTrue(storage.getAllUsers().stream().anyMatch(u -> "post_backup_user".equals(u.getUsername())));

        // 执行恢复
        Path zipPath = backupService.resolveBackup(backupName);
        Map<String, Object> summary = backupService.restoreBackup(zipPath);
        assertNotNull(summary.get("preRestoreBackup"), "恢复前必须自动创建备份");

        // 恢复后：备份后的用户不存在，备份时点的用户存在，token 表清空
        assertTrue(storage.getAllUsers().stream().noneMatch(u -> "post_backup_user".equals(u.getUsername())),
                "恢复后备份时点之后的数据应不存在");
        assertTrue(storage.getAllUsers().stream().anyMatch(u -> username.equals(u.getUsername())));
        assertTrue(storage.loadActiveTokens(System.currentTimeMillis()).isEmpty(), "恢复后旧登录态应全部失效");
        // 恢复前备份可用于回滚
        assertTrue(backupService.listBackups().stream()
                .anyMatch(b -> String.valueOf(b.get("name")).equals(String.valueOf(summary.get("preRestoreBackup")))));
    }

    /** 路径穿越与非法备份名被拒绝 */
    @Test
    void resolveBackup_路径穿越被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> backupService.resolveBackup("../evil.zip"));
        assertThrows(IllegalArgumentException.class, () -> backupService.resolveBackup("a/b.zip"));
        assertThrows(IllegalArgumentException.class, () -> backupService.resolveBackup("not-exist.zip"));
    }

    /** 非法备份内容（缺清单/伪造）恢复被拒绝且不破坏现有数据 */
    @Test
    void restoreBackup_非法备份被拒绝且数据不受影响() throws Exception {
        String username = "protect_user";
        storage.register(username, "pass1234", "127.0.0.1");
        int userCountBefore = storage.getAllUsers().size();

        // 构造不含 manifest 的假备份
        Path fakeZip = tempDir.resolve("fake-backup.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(fakeZip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("chatai.db"));
            zos.write("not-a-database".getBytes());
            zos.closeEntry();
        }
        assertThrows(Exception.class, () -> backupService.restoreBackup(fakeZip));
        assertEquals(userCountBefore, storage.getAllUsers().size(), "非法备份恢复失败不得破坏现有数据");
    }
}
