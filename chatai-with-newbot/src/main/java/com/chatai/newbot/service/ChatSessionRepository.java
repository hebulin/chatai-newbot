package com.chatai.newbot.service;

import com.chatai.newbot.exception.ChatSyncConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** SQLite 会话、回收站、同步状态与上下文摘要仓储。 */
@Repository
public class ChatSessionRepository {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /** 创建会话仓储。 */
    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbcTemplate.getDataSource())));
    }

    /** 读取旧整文档会话数据。 */
    public String loadLegacyData(String userId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT chat_data FROM t_chat_history WHERE user_id = ?", String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 删除用户全部会话及摘要状态。 */
    public void deleteUserData(String userId) {
        jdbcTemplate.update("DELETE FROM t_chat_history WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_user_state WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_context_summary WHERE user_id = ?", userId);
    }

    /** 判断用户同步状态行是否存在。 */
    public boolean hasUserState(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_chat_user_state WHERE user_id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    /** 读取用户会话同步状态。 */
    public Map<String, Object> loadUserState(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT last_chat_id, deleted_chat_ids, folders_json, sync_seq, folders_version FROM t_chat_user_state WHERE user_id = ?",
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 保存用户会话同步状态。 */
    public void saveUserState(String userId, String lastChatId, String deletedChatIdsJson,
                              String foldersJson, String updatedAt, long updatedAtTs) {
        int updated = jdbcTemplate.update(
                "UPDATE t_chat_user_state SET last_chat_id=?, deleted_chat_ids=?, folders_json=?, updated_at=?, updated_at_ts=? WHERE user_id=?",
                lastChatId, deletedChatIdsJson, foldersJson, updatedAt, updatedAtTs, userId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO t_chat_user_state (user_id, last_chat_id, deleted_chat_ids, folders_json, updated_at, updated_at_ts) VALUES (?,?,?,?,?,?)",
                    userId, lastChatId, deletedChatIdsJson, foldersJson, updatedAt, updatedAtTs);
        }
    }

    /** 读取用户单调递增的会话同步序列号。 */
    public long getHistoryVersion(String userId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT sync_seq FROM t_chat_user_state WHERE user_id = ?", Long.class, userId);
        return value == null ? 0L : value;
    }

    /** 原子递增并返回会话同步序列号。 */
    public long bumpSyncSequence(String userId) {
        jdbcTemplate.update("UPDATE t_chat_user_state SET sync_seq = sync_seq + 1 WHERE user_id = ?", userId);
        return getHistoryVersion(userId);
    }

    /** 原子递增并返回文件夹版本号。 */
    public long bumpFoldersVersion(String userId) {
        jdbcTemplate.update(
                "UPDATE t_chat_user_state SET folders_version = folders_version + 1 WHERE user_id = ?", userId);
        Long value = jdbcTemplate.queryForObject(
                "SELECT folders_version FROM t_chat_user_state WHERE user_id = ?", Long.class, userId);
        return value == null ? 0L : value;
    }

    /** 查询用户全部正常会话正文。 */
    public List<Map<String, Object>> listSessions(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, messages, meta FROM t_chat_session WHERE user_id = ? AND deleted_at IS NULL ORDER BY rowid",
                userId);
    }

    /** 查询用户全部正常会话摘要。 */
    public List<Map<String, Object>> listSummaries(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, title, preview, last_time, msg_count, meta, version FROM t_chat_session " +
                        "WHERE user_id = ? AND deleted_at IS NULL ORDER BY rowid", userId);
    }

    /** 查询单个正常会话。 */
    public Map<String, Object> findSession(String userId, String chatId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT messages, meta, version FROM t_chat_session WHERE user_id = ? AND chat_id = ? AND deleted_at IS NULL",
                userId, chatId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询会话当前乐观锁版本。 */
    public Integer findVersion(String userId, String chatId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT version FROM t_chat_session WHERE user_id = ? AND chat_id = ?", Integer.class, userId, chatId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询会话软删除时间。 */
    public String findDeletedAt(String userId, String chatId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT deleted_at FROM t_chat_session WHERE user_id = ? AND chat_id = ?", String.class, userId, chatId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询用户全部正常会话 ID。 */
    public List<String> listSessionIds(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id FROM t_chat_session WHERE user_id = ? AND deleted_at IS NULL", String.class, userId);
    }

    /** 读取与覆盖范围严格匹配的上下文摘要。 */
    public String findContextSummary(String userId, String chatId, int coveredCount) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content FROM t_chat_context_summary WHERE user_id=? AND chat_id=? AND covered_count=?",
                userId, chatId, coveredCount);
        if (rows.isEmpty()) return null;
        Object content = rows.get(0).get("content");
        return content instanceof String text && !text.isEmpty() ? text : null;
    }

    /** 新增或覆盖会话上下文摘要。 */
    public void saveContextSummary(String userId, String chatId, int coveredCount, String content) {
        jdbcTemplate.update(
                "INSERT INTO t_chat_context_summary (user_id, chat_id, covered_count, content, updated_at) VALUES (?,?,?,?,?) " +
                        "ON CONFLICT(user_id, chat_id) DO UPDATE SET covered_count=excluded.covered_count, content=excluded.content, updated_at=excluded.updated_at",
                userId, chatId, coveredCount, content, now());
    }

    /** 删除会话上下文摘要。 */
    public void deleteContextSummary(String userId, String chatId) {
        jdbcTemplate.update("DELETE FROM t_chat_context_summary WHERE user_id=? AND chat_id=?", userId, chatId);
    }

    /** 按字面关键字与可选时间、文件夹范围分页粗筛会话。 */
    public List<Map<String, Object>> search(String userId, String keyword, int limit, int offset,
                                            Long fromTs, Long toTs, String folderId) {
        String escaped = escapeLike(keyword);
        StringBuilder sql = new StringBuilder(
                "SELECT chat_id, title, messages, meta FROM t_chat_session " +
                        "WHERE user_id = ? AND deleted_at IS NULL AND (title LIKE ? ESCAPE '!' OR messages LIKE ? ESCAPE '!')");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.add("%" + escaped + "%");
        args.add("%" + escaped + "%");
        if (fromTs != null) { sql.append(" AND updated_at_ts >= ?"); args.add(fromTs); }
        if (toTs != null) { sql.append(" AND updated_at_ts <= ?"); args.add(toTs); }
        if (folderId != null && !folderId.isEmpty()) {
            sql.append(" AND meta LIKE ? ESCAPE '!'");
            args.add("%\"folderId\":\"" + escapeLike(folderId) + "\"%");
        }
        sql.append(" ORDER BY updated_at_ts DESC, chat_id ASC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 无版本约束新增或更新单个会话。 */
    public void upsert(String userId, String chatId, String messagesJson, String metaJson,
                       String title, String preview, String lastTime, int msgCount,
                       String updatedAt, long updatedAtTs) {
        upsertChecked(userId, chatId, messagesJson, metaJson, title, preview, lastTime,
                msgCount, updatedAt, updatedAtTs, null);
    }

    /** 带乐观锁版本约束新增或更新单个会话。 */
    public long upsertChecked(String userId, String chatId, String messagesJson, String metaJson,
                              String title, String preview, String lastTime, int msgCount,
                              String updatedAt, long updatedAtTs, Long expectedVersion) {
        Integer current = findVersion(userId, chatId);
        if (current == null) {
            if (expectedVersion != null && expectedVersion > 0) throw new ChatSyncConflictException(chatId, 0);
            jdbcTemplate.update(
                    "INSERT INTO t_chat_session (user_id, chat_id, messages, meta, title, preview, last_time, msg_count, version, updated_at, updated_at_ts) VALUES (?,?,?,?,?,?,?,?,1,?,?)",
                    userId, chatId, messagesJson, metaJson, title, preview, lastTime, msgCount, updatedAt, updatedAtTs);
            return 1;
        }
        if (expectedVersion != null && expectedVersion != current.longValue()) {
            throw new ChatSyncConflictException(chatId, current);
        }
        int updated = jdbcTemplate.update(
                "UPDATE t_chat_session SET messages=?, meta=COALESCE(?, meta), title=?, preview=?, last_time=?, " +
                        "msg_count=?, updated_at=?, updated_at_ts=?, version=version+1, deleted_at=NULL WHERE user_id=? AND chat_id=? AND version=?",
                messagesJson, metaJson, title, preview, lastTime, msgCount, updatedAt, updatedAtTs,
                userId, chatId, current);
        if (updated == 0) {
            Integer latest = findVersion(userId, chatId);
            throw new ChatSyncConflictException(chatId, latest == null ? 0 : latest);
        }
        return current + 1L;
    }

    /** 带乐观锁版本约束更新会话元信息。 */
    public boolean updateMetaChecked(String userId, String chatId, String metaJson,
                                     Long expectedVersion, String updatedAt, long updatedAtTs) {
        Integer current = findVersion(userId, chatId);
        if (current == null) return false;
        if (expectedVersion != null && expectedVersion > 0 && expectedVersion != current.longValue()) {
            throw new ChatSyncConflictException(chatId, current);
        }
        int updated = jdbcTemplate.update(
                "UPDATE t_chat_session SET meta=?, updated_at=?, updated_at_ts=?, version=version+1 " +
                        "WHERE user_id=? AND chat_id=? AND version=?",
                metaJson, updatedAt, updatedAtTs, userId, chatId, current);
        if (updated == 0) {
            Integer latest = findVersion(userId, chatId);
            throw new ChatSyncConflictException(chatId, latest == null ? 0 : latest);
        }
        return true;
    }

    /** 将指定正常会话移入回收站。 */
    public void softDelete(String userId, Collection<String> chatIds, String deletedAt) {
        if (chatIds == null || chatIds.isEmpty()) return;
        for (String chatId : chatIds) {
            jdbcTemplate.update(
                    "UPDATE t_chat_session SET deleted_at = ? WHERE user_id = ? AND chat_id = ? AND deleted_at IS NULL",
                    deletedAt, userId, chatId);
        }
    }

    /** 恢复指定回收站会话并返回实际恢复的 ID。 */
    public List<String> restore(String userId, Collection<String> chatIds) {
        List<String> restored = new ArrayList<>();
        if (chatIds == null || chatIds.isEmpty()) return restored;
        for (String chatId : chatIds) {
            int updated = jdbcTemplate.update(
                    "UPDATE t_chat_session SET deleted_at = NULL, version = version + 1, updated_at_ts = ? " +
                            "WHERE user_id = ? AND chat_id = ? AND deleted_at IS NOT NULL",
                    System.currentTimeMillis(), userId, chatId);
            if (updated > 0) restored.add(chatId);
        }
        return restored;
    }

    /** 彻底删除指定或全部回收站会话。 */
    public int purge(String userId, Collection<String> chatIds) {
        return transactionTemplate.execute(status -> purgeRows(userId, chatIds));
    }

    /** 在同一事务中清理目标回收站会话及其摘要，保留正常会话摘要。 */
    private int purgeRows(String userId, Collection<String> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM t_chat_context_summary WHERE user_id = ? AND chat_id IN " +
                    "(SELECT chat_id FROM t_chat_session WHERE user_id = ? AND deleted_at IS NOT NULL)", userId, userId);
            return jdbcTemplate.update(
                    "DELETE FROM t_chat_session WHERE user_id = ? AND deleted_at IS NOT NULL", userId);
        }
        int total = 0;
        for (String chatId : chatIds) {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM t_chat_session WHERE user_id = ? AND chat_id = ? AND deleted_at IS NOT NULL",
                    userId, chatId);
            if (deleted > 0) {
                jdbcTemplate.update(
                        "DELETE FROM t_chat_context_summary WHERE user_id = ? AND chat_id = ?", userId, chatId);
                total += deleted;
            }
        }
        return total;
    }

    /** 清理删除时间早于截止值的全部回收站会话。 */
    public int purgeBefore(String cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM t_chat_session WHERE deleted_at IS NOT NULL AND deleted_at < ?", cutoff);
    }

    /** 查询用户回收站摘要。 */
    public List<Map<String, Object>> listTrash(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, title, preview, last_time, msg_count, deleted_at, version FROM t_chat_session " +
                        "WHERE user_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", userId);
    }

    /** 物理删除指定用户会话。 */
    public void deleteSessions(String userId, Collection<String> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) return;
        for (String chatId : chatIds) {
            jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ? AND chat_id = ?", userId, chatId);
        }
    }

    /** 查询聊天与元信息的原始 JSON 文本。 */
    public List<String> listAllPayloads() {
        List<String> payloads = new ArrayList<>();
        payloads.addAll(jdbcTemplate.queryForList("SELECT chat_data FROM t_chat_history", String.class));
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT messages, meta FROM t_chat_session")) {
            if (row.get("messages") instanceof String value) payloads.add(value);
            if (row.get("meta") instanceof String value) payloads.add(value);
        }
        return payloads;
    }

    /** 查询全部非空分享快照 JSON。 */
    public List<String> listAllShareSnapshots() {
        return jdbcTemplate.queryForList(
                "SELECT snapshot_json FROM t_chat_share WHERE snapshot_json IS NOT NULL AND snapshot_json != ''", String.class);
    }

    /** 转义 LIKE 查询中的字面特殊字符。 */
    private String escapeLike(String keyword) {
        if (keyword == null) return "";
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** 生成数据库时间字符串。 */
    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
