package com.chatai.newbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 系统完整备份与恢复服务（仅管理员）。
 * 备份内容：SQLite 数据库一致性快照（VACUUM INTO，正确处理 WAL 与并发写入）、
 * 上传资源目录（data/uploads）、解密所需密钥文件（data/apikey.secret）与备份清单 manifest.json。
 * 恢复流程：格式/完整性/路径安全/密钥匹配校验 → 制作恢复前备份 → ATTACH 逐表导入（事务回滚保护）
 * → 替换资源与密钥 → 失效全部登录态与临时挑战。备份文件存放于 data/backups（非公开静态目录）。
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** 备份清单标识：应用与格式版本（恢复时校验兼容性） */
    public static final String MANIFEST_APP = "chatai-newbot";
    public static final int MANIFEST_FORMAT_VERSION = 1;
    /** 解压体积安全上限（防压缩包炸弹）：2GB */
    private static final long MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024;
    /** 单文件解压上限：1GB */
    private static final long MAX_ENTRY_BYTES = 1L * 1024 * 1024 * 1024;
    /** 备份保留数量（超出后删除最旧备份） */
    private static final int DEFAULT_KEEP = 5;
    /** 备份文件名时间格式 */
    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    /** 恢复时需要逐表导入的表清单（固定白名单，防止导入未知表结构） */
    private static final List<String> RESTORE_TABLES = List.of(
            "t_user", "t_model_config", "t_usage_log", "t_setting", "t_token",
            "t_chat_session", "t_chat_user_state", "t_chat_history", "t_chat_share",
            "t_custom_provider", "t_file_asset", "t_file_asset_grant", "t_announcement",
            "t_observability_hourly", "t_legacy_public_asset");

    private final JdbcTemplate jdbcTemplate;
    private final StorageManager storageManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 恢复维护状态：进行中时拒绝普通请求（由 AuthInterceptor 检查） */
    private final AtomicBoolean restoring = new AtomicBoolean(false);
    private volatile boolean recoveryRequired;

    public BackupService(JdbcTemplate jdbcTemplate, StorageManager storageManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageManager = storageManager;
    }

    /** 是否处于恢复维护状态（供拦截器拒绝写请求） */
    public boolean isRestoring() {
        return restoring.get();
    }

    /**
     * 每日凌晨 03:00 定时备份（t_setting: backup_auto_enabled，缺省开启，可在后台关闭）。
     * 保留策略：仅保留最近 DEFAULT_KEEP 份，超出自动删除最旧备份，避免无限增长。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledBackup() {
        String val = storageManager.getSetting("backup_auto_enabled");
        if ("false".equals(val)) return;
        try {
            Map<String, Object> info = createBackup();
            log.info("定时备份完成: {}", info.get("name"));
        } catch (Exception e) {
            log.error("定时备份失败", e);
        }
    }

    /** 数据目录（data/，相对工作目录） */
    private Path dataDir() {
        return Paths.get(System.getProperty("user.dir"), "data");
    }

    /** 备份目录（data/backups，非公开静态目录，禁止直接暴露） */
    private Path backupDir() throws IOException {
        Path dir = dataDir().resolve("backups");
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 创建完整系统备份：SQLite 一致性快照 + 上传资源 + 密钥文件 + 清单。
     * @return 备份文件名
     */
    public Map<String, Object> createBackup() throws Exception {
        Path dir = backupDir();
        Path zipPath = nextBackupPath(dir);
        String name = zipPath.getFileName().toString();
        Path snapshot = Files.createTempFile("chatai-backup-snapshot", ".db");
        try {
            // VACUUM INTO 生成一致性数据库快照（SQLite 官方在线备份方式，WAL 模式下安全）
            jdbcTemplate.execute("VACUUM INTO '" + snapshot.toAbsolutePath().toString().replace("'", "''") + "'");
            List<Map<String, Object>> files = new ArrayList<>();
            try (OutputStream fos = Files.newOutputStream(zipPath);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                // 数据库快照
                addEntry(zos, snapshot, "chatai.db", files);
                // 密钥文件（解密 API Key/2FA 密钥所必需；备份本身需妥善保管）
                Path secret = dataDir().resolve("apikey.secret");
                if (Files.exists(secret)) addEntry(zos, secret, "apikey.secret", files);
                // 上传资源（图片/附件解析文本）
                Path uploads = dataDir().resolve("uploads");
                if (Files.exists(uploads)) {
                    Files.walk(uploads).filter(Files::isRegularFile).forEach(f -> {
                        try {
                            addEntry(zos, f, "uploads/" + uploads.relativize(f).toString().replace('\\', '/'), files);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                // 备份清单：应用/格式版本、创建时间、文件校验（恢复时逐项校验）
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("app", MANIFEST_APP);
                manifest.put("formatVersion", MANIFEST_FORMAT_VERSION);
                String appVersion = BackupService.class.getPackage().getImplementationVersion();
                manifest.put("appVersion", appVersion != null ? appVersion : "dev");
                manifest.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                manifest.put("files", files);
                zos.putNextEntry(new ZipEntry("manifest.json"));
                zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zos.closeEntry();
            }
            pruneOldBackups();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name);
            info.put("size", Files.size(zipPath));
            info.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            info.put("fileCount", files.size());
            log.info("系统备份已创建: {}（{} 字节，{} 个文件）", name, info.get("size"), files.size());
            return info;
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    /**
     * 生成不会覆盖现有文件的备份路径；同一毫秒内并发创建时自动追加序号。
     */
    private Path nextBackupPath(Path dir) {
        String baseName = "backup-" + LocalDateTime.now().format(NAME_FMT);
        Path candidate = dir.resolve(baseName + ".zip");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(baseName + "-" + suffix++ + ".zip");
        }
        return candidate;
    }

    /** 向 zip 写入一个文件并记录其 SHA-256 校验到清单 */
    private void addEntry(ZipOutputStream zos, Path file, String entryName, List<Map<String, Object>> files) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        long size = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                zos.write(buf, 0, n);
                digest.update(buf, 0, n);
                size += n;
            }
        }
        zos.closeEntry();
        Map<String, Object> fi = new LinkedHashMap<>();
        fi.put("path", entryName);
        fi.put("size", size);
        fi.put("sha256", toHex(digest.digest()));
        files.add(fi);
    }

    /** 列出备份目录中的备份文件（按创建时间倒序） */
    public List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Path dir = backupDir();
            try (var stream = Files.list(dir)) {
                stream.filter(f -> f.getFileName().toString().endsWith(".zip"))
                        .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                        .forEach(f -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("name", f.getFileName().toString());
                            try {
                                item.put("size", Files.size(f));
                                item.put("createdAt", Files.getLastModifiedTime(f).toMillis());
                            } catch (IOException e) {
                                item.put("size", 0);
                            }
                            list.add(item);
                        });
            }
        } catch (Exception e) {
            log.warn("列出备份失败", e);
        }
        return list;
    }

    /** 解析备份文件路径（路径安全校验：仅允许备份目录内的纯文件名，防止路径穿越） */
    public Path resolveBackup(String name) throws IOException {
        if (name == null || name.isBlank() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("非法备份文件名");
        }
        Path path = backupDir().resolve(name).normalize();
        if (!path.startsWith(backupDir()) || !Files.exists(path)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        return path;
    }

    /** 删除指定备份文件 */
    public boolean deleteBackup(String name) throws IOException {
        return Files.deleteIfExists(resolveBackup(name));
    }

    /** 按保留策略删除最旧的备份（保留最近 DEFAULT_KEEP 个） */
    private void pruneOldBackups() {
        try {
            Path dir = backupDir();
            List<Path> zips = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(f -> f.getFileName().toString().endsWith(".zip")).forEach(zips::add);
            }
            zips.sort(Comparator.comparing(f -> f.getFileName().toString()));
            int excess = zips.size() - DEFAULT_KEEP;
            for (int i = 0; i < excess; i++) {
                Files.deleteIfExists(zips.get(i));
                log.info("备份保留策略：删除最旧备份 {}", zips.get(i).getFileName());
            }
        } catch (Exception e) {
            log.warn("清理旧备份失败", e);
        }
    }

    /**
     * 从备份恢复系统数据（维护状态执行，失败自动回滚数据库）：
     * 1) 解压到临时目录并校验清单/完整性/路径安全/解压体积
     * 2) 用备份内密钥试解密备份库中的加密字段，验证密钥匹配
     * 3) 制作恢复前备份（失败可手动回滚）
     * 4) ATTACH 备份库逐表导入（单事务，失败回滚），替换资源与密钥文件
     * 5) 失效全部登录态与临时认证挑战，刷新缓存
     * @param backupZip 备份 zip 文件路径（服务器备份目录或上传的临时文件）
     * @return 恢复摘要（表行数统计）
     */
    public Map<String, Object> restoreBackup(Path backupZip) throws Exception {
        if (!restoring.compareAndSet(false, true)) {
            throw new IllegalStateException("已有恢复任务进行中");
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("chatai-restore");
            // 1) 解压与校验
            Map<String, Object> manifest = extractAndValidate(backupZip, tempDir);
            // 2) 密钥匹配校验：备份内密钥必须能解密备份库中的密文
            verifyKeyMatch(tempDir);
            // 3) 恢复前备份当前数据（供失败时手动回滚）
            Map<String, Object> preRestore = createBackup();
            log.info("恢复前备份已创建: {}", preRestore.get("name"));
            // 4) 逐表导入（事务内，失败回滚）
            Map<String, Integer> tableCounts;
            try (RestoreFileSwap files = prepareFileRestore(tempDir)) {
                tableCounts = importDatabase(tempDir.resolve("chatai.db"), files);
            }
            // 数据库与文件提交后，缓存刷新失败必须保持维护状态，不能开放混合的新旧状态。
            recoveryRequired = true;
            storageManager.reloadAllCaches();
            recoveryRequired = false;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tables", tableCounts);
            result.put("preRestoreBackup", preRestore.get("name"));
            result.put("appVersion", manifest.get("appVersion"));
            result.put("createdAt", manifest.get("createdAt"));
            log.info("系统恢复完成: 来源={}, 表={}", backupZip.getFileName(), tableCounts);
            return result;
        } finally {
            restoring.set(recoveryRequired);
            if (tempDir != null) deleteRecursively(tempDir);
        }
    }

    /**
     * 解压备份到临时目录并校验：清单格式、文件 SHA-256 完整性、路径穿越与解压体积上限
     */
    private Map<String, Object> extractAndValidate(Path zipPath, Path targetDir) throws Exception {
        Map<String, String> entries = new HashMap<>();
        long totalBytes = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                // 路径安全：禁止绝对路径与 .. 穿越
                if (name.contains("..") || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
                    throw new IllegalArgumentException("备份包含非法路径: " + name);
                }
                if (entry.isDirectory()) continue;
                if (!(name.equals("manifest.json") || name.equals("chatai.db") || name.equals("apikey.secret")
                        || name.startsWith("uploads/")) || entries.containsKey(name) || entries.size() >= 50000) {
                    throw new IllegalArgumentException("备份含不支持、重复或过多的文件");
                }
                long entryLimit = name.equals("manifest.json") ? 8L * 1024 * 1024 : MAX_ENTRY_BYTES;
                if (entry.getSize() > entryLimit) {
                    throw new IllegalArgumentException("备份文件过大: " + name);
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir) || Files.exists(target)) {
                    throw new IllegalArgumentException("备份包含冲突或越界路径");
                }
                Files.createDirectories(target.getParent());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long entryBytes = 0;
                try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        totalBytes += count;
                        entryBytes += count;
                        if (totalBytes > MAX_EXTRACT_BYTES || entryBytes > entryLimit) {
                            throw new IllegalArgumentException("备份解压体积超过安全上限");
                        }
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                    }
                }
                entries.put(name, toHex(digest.digest()));
            }
        }
        // 清单校验
        if (!entries.containsKey("manifest.json")) {
            throw new IllegalArgumentException("备份缺少 manifest.json 清单");
        }
        Map<String, Object> manifest = objectMapper.readValue(targetDir.resolve("manifest.json").toFile(), Map.class);
        if (!MANIFEST_APP.equals(manifest.get("app"))) {
            throw new IllegalArgumentException("备份来源应用不匹配");
        }
        int formatVersion = manifest.get("formatVersion") instanceof Number n ? n.intValue() : -1;
        if (formatVersion != MANIFEST_FORMAT_VERSION) {
            throw new IllegalArgumentException("备份格式版本不兼容: " + formatVersion);
        }
        if (entries.get("chatai.db") == null) {
            throw new IllegalArgumentException("备份缺少数据库文件");
        }
        // 文件完整性校验（SHA-256）
        java.util.Set<String> declared = new java.util.HashSet<>();
        if (manifest.get("files") instanceof List<?> files && !files.isEmpty()) {
            for (Object fo : files) {
                if (!(fo instanceof Map)) throw new IllegalArgumentException("备份文件清单格式错误");
                String path = String.valueOf(((Map<?, ?>) fo).get("path"));
                String sha256 = String.valueOf(((Map<?, ?>) fo).get("sha256"));
                if (!declared.add(path) || path.equals("manifest.json")) {
                    throw new IllegalArgumentException("备份清单含重复或非法条目");
                }
                String hash = entries.get(path);
                if (hash == null) {
                    throw new IllegalArgumentException("备份缺少清单声明的文件: " + path);
                }
                if (!hash.equalsIgnoreCase(sha256)) {
                    throw new IllegalArgumentException("备份文件校验失败（数据损坏）: " + path);
                }
            }
        } else {
            throw new IllegalArgumentException("备份缺少文件校验清单");
        }
        if (declared.size() != entries.size() - 1) {
            throw new IllegalArgumentException("备份含未列入校验清单的文件");
        }
        return manifest;
    }

    /**
     * 密钥匹配校验：用备份内 apikey.secret 初始化临时解密器，
     * 试解密备份库中的密文字段；不匹配则拒绝恢复（避免恢复后全部 API Key 无法解密）
     */
    private void verifyKeyMatch(Path tempDir) throws Exception {
        Path secret = tempDir.resolve("apikey.secret");
        Path db = tempDir.resolve("chatai.db");
        if (!Files.exists(secret)) {
            // 旧备份无密钥文件：仅当库中无加密内容时才允许
            String encrypted = queryEncryptedSample(db);
            if (encrypted != null) {
                throw new IllegalArgumentException("备份缺少密钥文件，但数据库含加密内容，恢复后无法解密");
            }
            return;
        }
        try {
            if (java.util.Base64.getDecoder().decode(Files.readString(secret).trim()).length != 32) {
                throw new IllegalArgumentException("密钥长度无效");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("备份密钥格式无效", e);
        }
        String encrypted = queryEncryptedSample(db);
        if (encrypted == null) return; // 无加密内容，无需验证
        // 用备份密钥试解密（ApiKeyCrypto 支持指定密钥内容的静态校验方法）
        if (!ApiKeyCrypto.canDecryptWith(encrypted, Files.readString(secret).trim())) {
            throw new IllegalArgumentException("备份密钥与加密内容不匹配，恢复后 API Key 将无法解密，已中止");
        }
    }

    /** 从备份库中取样一条加密内容（模型 API Key 或用户 2FA 密钥），无加密内容返回 null */
    private String queryEncryptedSample(Path dbPath) {
        org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        JdbcTemplate backupJdbc = new JdbcTemplate(ds);
        try {
            List<String> keys = backupJdbc.queryForList(
                    "SELECT api_key FROM t_model_config WHERE api_key LIKE 'ENC:%' LIMIT 1", String.class);
            if (!keys.isEmpty()) return keys.get(0);
        } catch (Exception ignore) { /* 表不存在等情况视为无加密内容 */ }
        try {
            List<String> secrets = backupJdbc.queryForList(
                    "SELECT two_factor_secret FROM t_user WHERE two_factor_secret LIKE 'ENC:%' LIMIT 1", String.class);
            if (!secrets.isEmpty()) return secrets.get(0);
        } catch (Exception ignore) { /* 同上 */ }
        try {
            List<String> secrets = backupJdbc.queryForList(
                    "SELECT password_enc FROM t_chat_share WHERE password_enc LIKE 'ENC:%' LIMIT 1", String.class);
            if (!secrets.isEmpty()) return secrets.get(0);
        } catch (Exception ignore) { /* 兼容旧备份无此列 */ }
        return null;
    }

    /**
     * ATTACH 备份库逐表导入当前库（同一连接 + 单事务，失败整体回滚）。
     * 注意：ATTACH 仅对执行它的连接有效，JdbcTemplate 默认每条语句可能取不同连接，
     * 因此整个导入过程必须在同一 Connection 上完成。
     * 仅导入白名单表；列集合取两边交集（旧版本备份缺新列时跳过新列，新列按默认值）。
     * @return 每表导入行数
     */
    private Map<String, Integer> importDatabase(Path backupDb, RestoreFileSwap files) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String attachPath = backupDb.toAbsolutePath().toString().replace("'", "''");
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Map<String, Integer>>) conn -> {
            boolean oldAutoCommit = conn.getAutoCommit();
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("ATTACH DATABASE '" + attachPath + "' AS backup_src");
                conn.setAutoCommit(false);
                try {
                    for (String table : RESTORE_TABLES) {
                        // 备份库缺该表则跳过
                        boolean exists;
                        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                                "SELECT COUNT(*) FROM backup_src.sqlite_master WHERE type='table' AND name=?")) {
                            ps.setString(1, table);
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                exists = rs.next() && rs.getInt(1) > 0;
                            }
                        }
                        if (!exists) {
                            // 老备份没有显式旧公开资源表，不能沿用当前部署的授权。
                            if (table.equals("t_legacy_public_asset")) st.executeUpdate("DELETE FROM main.t_legacy_public_asset");
                            continue;
                        }
                        // 列交集：仅导入两边都存在的列（旧备份缺新列时用默认值补齐）
                        List<String> srcColNames = columnNames(conn, "backup_src", table);
                        List<String> dstColNames = columnNames(conn, "main", table);
                        srcColNames.retainAll(dstColNames);
                        if (srcColNames.isEmpty()) continue;
                        String cols = String.join(", ", srcColNames);
                        st.executeUpdate("DELETE FROM main." + table);
                        st.executeUpdate("INSERT INTO main." + table + " (" + cols + ") SELECT " + cols + " FROM backup_src." + table);
                        try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM main." + table)) {
                            counts.put(table, rs.next() ? rs.getInt(1) : 0);
                        }
                    }
                    st.executeUpdate("DELETE FROM main.t_token");
                    files.apply();
                    conn.commit();
                    // 提交成功即进入待刷新状态；后续连接清理异常也不能提前开放旧缓存。
                    recoveryRequired = true;
                } catch (Exception e) {
                    try {
                        conn.rollback();
                    } catch (Exception rollbackErr) {
                        recoveryRequired = true;
                        log.error("恢复回滚失败", rollbackErr);
                    }
                    try {
                        files.rollback();
                    } catch (Exception rollbackErr) {
                        recoveryRequired = true;
                        e.addSuppressed(rollbackErr);
                        log.error("资源恢复回滚失败，保持维护状态", rollbackErr);
                    }
                    throw new IllegalStateException("数据库恢复失败，已回滚: " + e.getMessage(), e);
                } finally {
                    conn.setAutoCommit(oldAutoCommit);
                    try {
                        st.execute("DETACH DATABASE backup_src");
                    } catch (Exception ignore) { /* 已回滚或未附加成功 */ }
                }
            }
            return counts;
        });
    }

    /** 查询指定 schema 下表的列名列表（同一连接上执行，供 ATTACH 导入用） */
    private List<String> columnNames(java.sql.Connection conn, String schema, String table) throws java.sql.SQLException {
        List<String> names = new ArrayList<>();
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA " + schema + ".table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    /** 完整暂存新资源，之后由数据库事务协调文件切换与补偿回滚。 */
    RestoreFileSwap prepareFileRestore(Path tempDir) throws IOException {
        return new RestoreFileSwap(tempDir, dataDir());
    }

    /** 递归删除目录（临时目录清理用） */
    private void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(f -> {
                        try { Files.deleteIfExists(f); } catch (IOException ignore) { }
                    });
        } catch (Exception ignore) { /* 清理失败不阻断主流程 */ }
    }

    /** 字节数组转十六进制字符串（SHA-256 摘要用） */
    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
