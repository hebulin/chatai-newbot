package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
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
 * - 使用记录：清理 t_usage_log 中保留期之外的旧行，
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
    /** 消息中的上传文件引用：/api/files/img/{yyyyMM}/{file} 或 /api/files/doc/{yyyyMM}/{file} */
    private static final Pattern UPLOAD_REF = Pattern.compile("/api/files/(img|doc)/(\\d{6})/([a-zA-Z0-9._-]+)");

    private final SqliteStorageService sqliteStorage;
    private final StorageManager storageManager;
    private final AuditLogService auditLogService;

    public DataCleanupService(SqliteStorageService sqliteStorage, StorageManager storageManager,
                              AuditLogService auditLogService) {
        this.sqliteStorage = sqliteStorage;
        this.storageManager = storageManager;
        this.auditLogService = auditLogService;
    }

    /**
     * 每天 04:00 清理 SQLite 中保留期之外的使用记录。
     */
    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupUsageLogs() {
        int retentionDays = getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        String cutoff = LocalDate.now().minusDays(retentionDays)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int deletedRows = 0;
        try {
            deletedRows = sqliteStorage.deleteUsageLogsBefore(cutoff);
        } catch (Exception e) {
            log.warn("清理过期使用记录SQLite行失败", e);
        }
        if (deletedRows > 0) {
            log.info("使用记录清理完成: 保留{}天, 删除SQLite记录{}条", retentionDays, deletedRows);
        }
    }

    /**
     * 每天 04:10 清理保留期之外的审计日志
     */
    @Scheduled(cron = "0 10 4 * * ?")
    public void cleanupAuditLogs() {
        try {
            int deleted = auditLogService.purgeBefore(AuditLogService.DEFAULT_RETENTION_DAYS);
            if (deleted > 0) {
                log.info("审计日志清理完成: 保留{}天, 删除{}条",
                        AuditLogService.DEFAULT_RETENTION_DAYS, deleted);
            }
        } catch (Exception e) {
            log.warn("清理过期审计日志失败", e);
        }
    }

    /**
     * 每天 04:30 清理会话历史中已无引用的孤儿上传文件（图片与附件解析文本）。
     * 引用收集覆盖：正常会话、回收站会话、旧整文档备份与有效分享快照，
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
     * 每天 04:40 清理回收站中超过保留期限的会话（彻底删除，不可恢复）。
     * 保留天数由 t_setting 的 trash_retention_days 控制（默认 30 天，0=永久保留）
     */
    @Scheduled(cron = "0 40 4 * * ?")
    public void cleanupExpiredTrash() {
        int retentionDays = getTrashRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        String cutoff = LocalDate.now().minusDays(retentionDays)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        try {
            int purged = sqliteStorage.purgeTrashBefore(cutoff + " 00:00:00");
            if (purged > 0) {
                log.info("回收站过期会话清理完成: 保留{}天, 彻底删除{}个会话", retentionDays, purged);
            }
        } catch (Exception e) {
            log.warn("清理回收站过期会话失败", e);
        }
    }

    /**
     * 读取回收站保留天数配置（t_setting: trash_retention_days，缺省 30，0=永久保留）
     */
    private int getTrashRetentionDays() {
        try {
            String val = storageManager.getSetting("trash_retention_days");
            return (val == null || val.isEmpty()) ? 30 : Math.max(0, Integer.parseInt(val));
        } catch (Exception e) {
            return 30;
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
     * 汇总全部上传文件引用，键为 "img/{yyyyMM}/{file}" 或 "doc/{yyyyMM}/{file}"
     * 来源：会话行（含回收站）、旧整文档备份、分享快照——防止源会话已删但分享仍有效的资源被误删
     * @throws Exception 任一数据源读取失败时抛出（调用方放弃本次清理）
     */
    private Set<String> collectUploadReferences() throws Exception {
        Set<String> refs = new HashSet<>();
        for (String payload : sqliteStorage.listAllChatPayloads()) {
            extractRefs(payload, refs);
        }
        for (String snapshot : sqliteStorage.listAllShareSnapshots()) {
            extractRefs(snapshot, refs);
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
