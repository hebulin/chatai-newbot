package com.chatai.newbot.service;

import com.chatai.newbot.model.Announcement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQLite 系统公告仓储，包含当前启用公告的失效式缓存。 */
@Repository
public class AnnouncementRepository {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;
    private volatile Optional<Announcement> enabledCache;
    private final RowMapper<Announcement> rowMapper = (resultSet, rowNumber) -> {
        Announcement announcement = new Announcement();
        announcement.setId(resultSet.getString("id"));
        announcement.setTitle(resultSet.getString("title"));
        announcement.setContent(resultSet.getString("content"));
        announcement.setStartAt(resultSet.getString("start_at"));
        announcement.setEndAt(resultSet.getString("end_at"));
        announcement.setEnabled(resultSet.getInt("enabled") == 1);
        announcement.setCreatedAt(resultSet.getString("created_at"));
        announcement.setUpdatedAt(resultSet.getString("updated_at"));
        return announcement;
    };

    /** 创建公告仓储。 */
    public AnnouncementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增公告并使启用公告缓存失效。 */
    public Announcement add(Announcement announcement) {
        if (announcement.getId() == null || announcement.getId().isEmpty()) {
            announcement.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (announcement.getCreatedAt() == null) announcement.setCreatedAt(now());
        if (announcement.getUpdatedAt() == null) announcement.setUpdatedAt(announcement.getCreatedAt());
        jdbcTemplate.update(
                "INSERT INTO t_announcement (id, title, content, start_at, end_at, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                announcement.getId(), announcement.getTitle(), announcement.getContent(), announcement.getStartAt(),
                announcement.getEndAt(), announcement.isEnabled() ? 1 : 0,
                announcement.getCreatedAt(), announcement.getUpdatedAt());
        invalidateCache();
        return announcement;
    }

    /** 按 ID 查询公告。 */
    public Announcement findById(String id) {
        List<Announcement> rows = jdbcTemplate.query("SELECT * FROM t_announcement WHERE id = ?", rowMapper, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询全部公告，最近更新优先。 */
    public List<Announcement> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM t_announcement ORDER BY updated_at DESC, created_at DESC", rowMapper);
    }

    /** 查询当前启用公告并复用失效式缓存。 */
    public Announcement findEnabled() {
        Optional<Announcement> cached = enabledCache;
        if (cached == null) {
            List<Announcement> rows = jdbcTemplate.query(
                    "SELECT * FROM t_announcement WHERE enabled = 1 ORDER BY updated_at DESC LIMIT 1", rowMapper);
            cached = rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            enabledCache = cached;
        }
        return cached.orElse(null);
    }

    /** 更新公告内容及启用状态。 */
    public void update(Announcement announcement) {
        jdbcTemplate.update(
                "UPDATE t_announcement SET title = ?, content = ?, start_at = ?, end_at = ?, enabled = ?, updated_at = ? WHERE id = ?",
                announcement.getTitle(), announcement.getContent(), announcement.getStartAt(), announcement.getEndAt(),
                announcement.isEnabled() ? 1 : 0, announcement.getUpdatedAt(), announcement.getId());
        invalidateCache();
    }

    /** 下线指定公告之外的全部公告。 */
    public void disableOthers(String exceptId) {
        jdbcTemplate.update("UPDATE t_announcement SET enabled = 0 WHERE id <> ?", exceptId);
        invalidateCache();
    }

    /** 删除公告并返回是否命中记录。 */
    public boolean delete(String id) {
        boolean deleted = jdbcTemplate.update("DELETE FROM t_announcement WHERE id = ?", id) > 0;
        invalidateCache();
        return deleted;
    }

    /** 使当前启用公告缓存失效。 */
    public void invalidateCache() {
        enabledCache = null;
    }

    /** 生成数据库时间字符串。 */
    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
