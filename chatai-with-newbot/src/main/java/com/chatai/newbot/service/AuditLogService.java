package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务：记录登录、用户管理、模型/设置变更等敏感操作，
 * 持久化到 SQLite t_audit_log 表，供后台按用户/操作/时间段分页查询。
 * 记录失败只打日志不抛异常，审计不阻断业务主流程。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** 审计日志默认保留天数（超期在每日清理任务中删除） */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 初始化：创建审计日志表与索引（幂等）
     */
    @PostConstruct
    public void init() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_audit_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp TEXT NOT NULL," +
                "user_id TEXT," +
                "username TEXT," +
                "action TEXT NOT NULL," +
                "detail TEXT," +
                "ip TEXT" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON t_audit_log(timestamp)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_user ON t_audit_log(username)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON t_audit_log(action)");
    }

    /**
     * 记录一条审计日志（失败不抛异常，不影响业务）
     * @param userId 操作人用户ID（可为 null，如登录失败）
     * @param username 操作人用户名
     * @param action 操作类型编码（如 login / user.delete / model.update）
     * @param detail 操作详情描述
     * @param ip 操作来源IP
     */
    public void record(String userId, String username, String action, String detail, String ip) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO t_audit_log (timestamp, user_id, username, action, detail, ip) VALUES (?,?,?,?,?,?)",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    userId, username, action, detail, ip);
        } catch (Exception e) {
            log.warn("写入审计日志失败: action={}", action, e);
        }
    }

    /**
     * 分页查询审计日志（时间倒序），支持按用户名/操作类型/日期范围过滤
     * @param username 用户名（模糊匹配，可空）
     * @param action 操作类型（精确匹配，可空）
     * @param startDate 开始日期 yyyy-MM-dd（含当天，可空）
     * @param endDate 结束日期 yyyy-MM-dd（含当天，可空）
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return { data, total, page, size, totalPages }
     */
    public Map<String, Object> query(String username, String action,
                                     String startDate, String endDate, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.trim().isEmpty()) {
            where.append(" AND username LIKE ?");
            args.add("%" + username.trim() + "%");
        }
        if (action != null && !action.trim().isEmpty()) {
            where.append(" AND action = ?");
            args.add(action.trim());
        }
        if (startDate != null && !startDate.isEmpty()) {
            where.append(" AND timestamp >= ?");
            args.add(startDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isEmpty()) {
            where.append(" AND timestamp <= ?");
            args.add(endDate + " 23:59:59");
        }

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log" + where, Integer.class, args.toArray());
        int totalCount = total == null ? 0 : total;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / size));
        int safePage = Math.min(Math.max(1, page), totalPages);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((safePage - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, timestamp, user_id, username, action, detail, ip FROM t_audit_log" + where +
                        " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", rows);
        result.put("total", totalCount);
        result.put("page", safePage);
        result.put("size", size);
        result.put("totalPages", totalPages);
        return result;
    }

    /**
     * 查询已出现过的操作类型列表（前端筛选下拉用）
     */
    public List<String> listActions() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT action FROM t_audit_log ORDER BY action", String.class);
    }

    /**
     * 删除保留期之前的审计日志（每日清理任务调用）
     * @param retentionDays 保留天数
     * @return 删除条数
     */
    public int purgeBefore(int retentionDays) {
        String cutoff = LocalDateTime.now().minusDays(retentionDays)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return jdbcTemplate.update("DELETE FROM t_audit_log WHERE timestamp < ?", cutoff);
    }
}
