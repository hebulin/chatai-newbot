package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据定期清理服务（每日凌晨低峰期执行）：
 * - 使用记录：清理保留期之外的 usage_logs JSON 日志文件与 t_usage_log 旧行，
 *   保留天数由 t_setting 的 usage_log_retention_days 控制（默认 120 天，0=不清理）
 * - 上传文件：清理会话历史中已无引用的孤儿图片/附件解析文本
 *   （会话删除后对应的上传文件不会随之删除，长期运行会累积占用磁盘）
 */
@Service
public class DataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(DataCleanupService.class);

    /** usage_logs 默认保留天数 */
    private static final int DEFAULT_RETENTION_DAYS = 120;
    /** 孤儿文件保护期：落盘不足该时长的文件不删除，防止误删刚上传、会话尚未同步的附件 */
    private static final long ORPHAN_GRACE_MS = 24L * 60 * 60 * 1000;
    /** usage_logs 日志文件名：usage_logs_yyyy-MM-dd.json */
    private static final Pattern USAGE_LOG_FILE = Pattern.compile("^usage_logs_(\\d{4}-\\d{2}-\\d{2})\\.json$");
    /** 消息中的上传文件引用：/api/files/img/{yyyyMM}/{file} 或 /api/files/doc/{yyyyMM}/{file} */
    private static final Pattern UPLOAD_REF = Pattern.compile("/api/files/(img|doc)/(\\d{6})/([a-zA-Z0-9._-]+)");

    private final SqliteStorageService sqliteStorage;
    private final StorageManager storageManager;

    public DataCleanupService(SqliteStorageService sqliteStorage, StorageManager storageManager) {
        this.sqliteStorage = sqliteStorage;
        this.storageManager = storageManager;
    }

    /**
     * 每天 04:00 清理保留期之外的使用记录（JSON 日志文件 + SQLite 表旧行）
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupUsageLogs() {
        int retentionDays = getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        String cutoff = LocalDate.now().minusDays(retentionDays)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int deletedFiles = 0;
        try {
            File dataDir = Paths.get(System.getProperty("user.dir"), "data").toFile();
            File[] files = dataDir.listFiles((d, name) -> USAGE_LOG_FILE.matcher(name).matches());
            if (files != null) {
                for (File file : files) {
                    Matcher m = USAGE_LOG_FILE.matcher(file.getName());
                    if (m.matches() && m.group(1).compareTo(cutoff) < 0 && file.delete()) {
                        deletedFiles++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("清理过期使用记录JSON文件失败", e);
        }
        int deletedRows = 0;
        try {
            deletedRows = sqliteStorage.deleteUsageLogsBefore(cutoff);
        } catch (Exception e) {
            log.warn("清理过期使用记录SQLite行失败", e);
        }
        if (deletedFiles > 0 || deletedRows > 0) {
            log.info("使用记录清理完成: 保留{}天, 删除JSON文件{}个, 删除SQLite记录{}条",
                    retentionDays, deletedFiles, deletedRows);
        }
    }

    /**
     * 每天 04:30 清理会话历史中已无引用的孤儿上传文件（图片与附件解析文本）。
     * 引用收集覆盖 SQLite 按会话行/旧整文档备份与 JSON 模式历史文件，
     * 对原始 JSON 文本正则提取，任一来源读取失败则放弃本次清理，宁可漏删不可误删。
     */
    @Scheduled(cron = "0 30 4 * * ?")
    public void cleanupOrphanUploads() {
        Set<String> referenced;
        try {
            referenced = collectUploadReferences();
        } catch (Exception e) {
            log.warn("收集上传文件引用失败，跳过本次孤儿文件清理", e);
            return;
        }
        long now = System.currentTimeMillis();
        Path uploadDir = Paths.get(System.getProperty("user.dir"), "data", "uploads");
        int deleted = deleteOrphans(uploadDir, "img", referenced, now)
                + deleteOrphans(uploadDir.resolve("doc"), "doc", referenced, now);
        if (deleted > 0) {
            log.info("孤儿上传文件清理完成: 删除{}个文件（现存引用{}条）", deleted, referenced.size());
        }
    }

    /**
     * 读取 usage_logs 保留天数配置（t_setting: usage_log_retention_days，缺省 120，0=不清理）
     */
    private int getRetentionDays() {
        try {
            String val = storageManager.getSetting("usage_log_retention_days");
            return (val == null || val.isEmpty()) ? DEFAULT_RETENTION_DAYS : Math.max(0, Integer.parseInt(val));
        } catch (Exception e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /**
     * 汇总全部会话历史中的上传文件引用，键为 "img/{yyyyMM}/{file}" 或 "doc/{yyyyMM}/{file}"
     * @throws Exception 任一数据源读取失败时抛出（调用方放弃本次清理）
     */
    private Set<String> collectUploadReferences() throws Exception {
        Set<String> refs = new HashSet<>();
        for (String payload : sqliteStorage.listAllChatPayloads()) {
            extractRefs(payload, refs);
        }
        // JSON 模式历史文件（含归档），切换过存储模式的旧数据也一并纳入引用
        Path chatDir = Paths.get(System.getProperty("user.dir"), "data", "chat_history");
        File dir = chatDir.toFile();
        File[] files = dir.exists() ? dir.listFiles((d, name) -> name.endsWith(".json")) : null;
        if (files != null) {
            for (File file : files) {
                extractRefs(Files.readString(file.toPath(), StandardCharsets.UTF_8), refs);
            }
        }
        return refs;
    }

    /** 从原始 JSON 文本中正则提取上传文件引用 */
    private void extractRefs(String payload, Set<String> refs) {
        if (payload == null || payload.isEmpty()) return;
        Matcher m = UPLOAD_REF.matcher(payload);
        while (m.find()) {
            refs.add(m.group(1) + "/" + m.group(2) + "/" + m.group(3));
        }
    }

    /**
     * 删除指定根目录下无引用且已过保护期的孤儿文件
     * @param root 扫描根目录（图片为 uploads/，附件文本为 uploads/doc/）
     * @param type 引用键前缀（img/doc）
     * @param referenced 现存引用集合
     * @param now 当前时间戳
     * @return 删除的文件数
     */
    private int deleteOrphans(Path root, String type, Set<String> referenced, long now) {
        File rootDir = root.toFile();
        File[] monthDirs = rootDir.exists()
                ? rootDir.listFiles(f -> f.isDirectory() && f.getName().matches("\\d{6}")) : null;
        if (monthDirs == null) return 0;
        int deleted = 0;
        for (File monthDir : monthDirs) {
            File[] files = monthDir.listFiles(File::isFile);
            if (files == null) continue;
            for (File file : files) {
                String key = type + "/" + monthDir.getName() + "/" + file.getName();
                if (referenced.contains(key)) continue;
                if (now - file.lastModified() < ORPHAN_GRACE_MS) continue;
                if (file.delete()) {
                    deleted++;
                } else {
                    log.warn("删除孤儿上传文件失败: {}", key);
                }
            }
            // 月份目录清空后一并移除
            File[] remain = monthDir.listFiles();
            if (remain != null && remain.length == 0 && monthDir.delete()) {
                log.info("已移除空的上传月份目录: {}/{}", type, monthDir.getName());
            }
        }
        return deleted;
    }
}
