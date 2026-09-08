package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatShare;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.List;

/**
 * 会话分享仓储，集中管理 t_chat_share 的查询与写入。
 */
@Repository
public class ChatShareRepository {
    private final JdbcTemplate jdbcTemplate;

    /** 会话分享行映射器。 */
    private final RowMapper<ChatShare> rowMapper = (ResultSet rs, int rowNum) -> {
        ChatShare share = new ChatShare();
        share.setId(rs.getString("id"));
        share.setChatId(rs.getString("chat_id"));
        share.setUserId(rs.getString("user_id"));
        share.setUserName(rs.getString("user_name"));
        share.setTitle(rs.getString("title"));
        share.setCreatedAt(rs.getString("created_at"));
        share.setExpiresAt(rs.getString("expires_at"));
        share.setSnapshotJson(rs.getString("snapshot_json"));
        share.setPasswordHash(rs.getString("password_hash"));
        share.setPasswordEnc(rs.getString("password_enc"));
        share.setAccessCount(rs.getInt("access_count"));
        share.setMaxViews(rs.getInt("max_views"));
        share.setSanitized(rs.getInt("sanitized") == 1);
        return share;
    };

    /** 创建会话分享仓储。 */
    public ChatShareRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 保存一条完整的分享记录。 */
    public ChatShare insert(ChatShare share) {
        jdbcTemplate.update(
                "INSERT INTO t_chat_share (id, chat_id, user_id, user_name, title, created_at, expires_at, snapshot_json, password_hash, password_enc, access_count, max_views, sanitized) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                share.getId(), share.getChatId(), share.getUserId(), share.getUserName(), share.getTitle(),
                share.getCreatedAt(), share.getExpiresAt(), share.getSnapshotJson(), share.getPasswordHash(),
                share.getPasswordEnc(), share.getAccessCount(), share.getMaxViews(), share.isSanitized() ? 1 : 0);
        return share;
    }

    /** 按分享码查询分享记录。 */
    public ChatShare findById(String id) {
        List<ChatShare> rows = jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE id = ?", rowMapper, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询指定用户创建的全部分享记录。 */
    public List<ChatShare> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE user_id = ? ORDER BY created_at DESC", rowMapper, userId);
    }

    /** 查询后台管理所需的全部分享记录。 */
    public List<ChatShare> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM t_chat_share ORDER BY created_at DESC", rowMapper);
    }

    /** 查询用户对指定会话已创建的分享记录。 */
    public ChatShare findByUserIdAndChatId(String userId, String chatId) {
        List<ChatShare> rows = jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE user_id = ? AND chat_id = ?", rowMapper, userId, chatId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按分享码删除分享记录。 */
    public boolean deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM t_chat_share WHERE id = ?", id) > 0;
    }

    /** 更新分享过期时间。 */
    public void updateExpiry(String id, String expiresAt) {
        jdbcTemplate.update("UPDATE t_chat_share SET expires_at = ? WHERE id = ?", expiresAt, id);
    }

    /** 更新复用分享的快照与安全字段，并重置访问次数。 */
    public void updateDetails(ChatShare share) {
        jdbcTemplate.update("UPDATE t_chat_share SET title=?, expires_at=?, snapshot_json=?, password_hash=?, password_enc=?, "
                        + "access_count=0, max_views=?, sanitized=? WHERE id=?",
                share.getTitle(), share.getExpiresAt(), share.getSnapshotJson(), share.getPasswordHash(),
                share.getPasswordEnc(), share.getMaxViews(), share.isSanitized() ? 1 : 0, share.getId());
        share.setAccessCount(0);
    }

    /** 更新访问密码、访问上限以及可选的访问计数。 */
    public boolean updateSecurity(String id, String passwordHash, String passwordEnc,
                                  int maxViews, boolean resetAccessCount) {
        String sql = "UPDATE t_chat_share SET password_hash=?, password_enc=?, max_views=?"
                + (resetAccessCount ? ", access_count=0" : "") + " WHERE id=?";
        return jdbcTemplate.update(sql, passwordHash, passwordEnc, maxViews, id) > 0;
    }

    /** 原子占用一次分享访问额度。 */
    public boolean claimAccess(String id) {
        return jdbcTemplate.update("UPDATE t_chat_share SET access_count=access_count+1 "
                + "WHERE id=? AND (max_views<=0 OR access_count<max_views)", id) > 0;
    }
}
