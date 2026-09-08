package com.chatai.newbot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** SQLite 登录 Token 仓储。 */
@Repository
public class TokenRepository {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;

    /** 创建 Token 仓储。 */
    public TokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增或覆盖登录 Token。 */
    public void insert(String token, String userId, String ip, String browser, long expiresAt) {
        jdbcTemplate.update(
                "INSERT OR REPLACE INTO t_token (token, user_id, ip, browser, created_at, expires_at) VALUES (?,?,?,?,?,?)",
                token, userId, ip, browser, LocalDateTime.now().format(TIME_FORMAT), expiresAt);
    }

    /** 查询用户的全部登录设备 Token。 */
    public List<Map<String, Object>> listByUser(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT token, ip, browser, created_at, expires_at FROM t_token WHERE user_id = ? ORDER BY created_at DESC",
                userId);
    }

    /** 删除指定 Token。 */
    public void delete(String token) {
        jdbcTemplate.update("DELETE FROM t_token WHERE token = ?", token);
    }

    /** 删除指定用户的全部 Token。 */
    public void deleteByUser(String userId) {
        jdbcTemplate.update("DELETE FROM t_token WHERE user_id = ?", userId);
    }

    /** 更新 Token 的过期时间。 */
    public void updateExpiry(String token, long expiresAt) {
        jdbcTemplate.update("UPDATE t_token SET expires_at = ? WHERE token = ?", expiresAt, token);
    }

    /** 更新 Token 的浏览器描述。 */
    public void updateBrowser(String token, String browser) {
        jdbcTemplate.update("UPDATE t_token SET browser = ? WHERE token = ?", browser, token);
    }

    /** 删除所有过期 Token 并返回删除数量。 */
    public int deleteExpired(long now) {
        return jdbcTemplate.update("DELETE FROM t_token WHERE expires_at < ?", now);
    }

    /** 加载全部未过期 Token。 */
    public List<Map<String, Object>> loadActive(long now) {
        return jdbcTemplate.queryForList(
                "SELECT token, user_id, ip, expires_at FROM t_token WHERE expires_at >= ?", now);
    }
}
