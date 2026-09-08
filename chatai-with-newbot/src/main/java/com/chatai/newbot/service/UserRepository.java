package com.chatai.newbot.service;

import com.chatai.newbot.model.PromptPreset;
import com.chatai.newbot.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SQLite 用户仓储，负责用户映射、缓存、凭据与级联数据持久化。 */
@Repository
public class UserRepository {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, User> cache = new ConcurrentHashMap<>();
    private final RowMapper<User> rowMapper = (resultSet, rowNumber) -> {
        User user = new User();
        user.setId(resultSet.getString("id"));
        user.setUsername(resultSet.getString("username"));
        user.setPassword(resultSet.getString("password"));
        user.setRole(resultSet.getString("role"));
        user.setCreatedAt(resultSet.getString("created_at"));
        user.setLastLoginAt(resultSet.getString("last_login_at"));
        user.setLastLoginIp(resultSet.getString("last_login_ip"));
        user.setLastLoginBrowser(resultSet.getString("last_login_browser"));
        user.setDisplayName(resultSet.getString("display_name"));
        user.setEmail(resultSet.getString("email"));
        user.setPhone(resultSet.getString("phone"));
        user.setDepartment(resultSet.getString("department"));
        user.setJobTitle(resultSet.getString("job_title"));
        user.setBio(resultSet.getString("bio"));
        user.setAvatarType(resultSet.getString("avatar_type"));
        user.setAvatarValue(resultSet.getString("avatar_value"));
        user.setAllowedModelIds(parseStringList(resultSet.getString("allowed_model_ids")));
        user.setSystemPrompt(resultSet.getString("system_prompt"));
        user.setPromptPresets(parsePromptPresets(resultSet.getString("prompt_presets")));
        user.setDisabled(resultSet.getInt("disabled") == 1);
        user.setDailyLimitType(resultSet.getString("daily_limit_type"));
        user.setDailyLimitValue(resultSet.getInt("daily_limit_value"));
        user.setTwoFactorEnabled(resultSet.getInt("two_factor_enabled") == 1);
        user.setTwoFactorSecret(resultSet.getString("two_factor_secret"));
        user.setRecoveryCodeHashes(parseStringList(resultSet.getString("recovery_code_hashes")));
        user.setTwoFactorLastUsedStep(resultSet.getLong("two_factor_last_used_step"));
        return user;
    };

    /** 创建用户仓储。 */
    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbcTemplate.getDataSource())));
    }

    /** 统计指定用户名的用户数。 */
    public int countByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    /** 插入完整用户记录。 */
    public void insert(User user) {
        jdbcTemplate.update(
                "INSERT INTO t_user (id, username, password, role, created_at, last_login_at, last_login_ip, last_login_browser, display_name, email, phone, department, job_title, bio, avatar_type, avatar_value, allowed_model_ids, system_prompt, disabled, daily_limit_type, daily_limit_value, prompt_presets, two_factor_enabled, two_factor_secret, recovery_code_hashes, two_factor_last_used_step) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                user.getId(), user.getUsername(), user.getPassword(), user.getRole(), user.getCreatedAt(),
                user.getLastLoginAt(), user.getLastLoginIp(), user.getLastLoginBrowser(), user.getDisplayName(),
                user.getEmail(), user.getPhone(), user.getDepartment(), user.getJobTitle(), user.getBio(),
                user.getAvatarType(), user.getAvatarValue(), toJson(user.getAllowedModelIds()), user.getSystemPrompt(),
                user.isDisabled() ? 1 : 0, user.getDailyLimitType(), user.getDailyLimitValue(),
                toJson(user.getPromptPresets()), user.isTwoFactorEnabled() ? 1 : 0, user.getTwoFactorSecret(),
                toJson(user.getRecoveryCodeHashes()), user.getTwoFactorLastUsedStep());
        invalidate(user.getId());
    }

    /** 按用户名查询用户。 */
    public User findByUsername(String username) {
        List<User> rows = jdbcTemplate.query("SELECT * FROM t_user WHERE username = ?", rowMapper, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询全部用户。 */
    public List<User> findAll() {
        return jdbcTemplate.query("SELECT * FROM t_user", rowMapper);
    }

    /** 按用户名关键字分页查询用户。 */
    public List<User> query(String keyword, int offset, int limit) {
        if (keyword == null || keyword.isEmpty()) {
            return jdbcTemplate.query(
                    "SELECT * FROM t_user ORDER BY created_at ASC LIMIT ? OFFSET ?", rowMapper, limit, offset);
        }
        return jdbcTemplate.query(
                "SELECT * FROM t_user WHERE username LIKE ? ORDER BY created_at ASC LIMIT ? OFFSET ?",
                rowMapper, "%" + keyword + "%", limit, offset);
    }

    /** 统计用户名关键字命中的用户数量。 */
    public int count(String keyword) {
        Integer count = keyword == null || keyword.isEmpty()
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_user", Integer.class)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM t_user WHERE username LIKE ?", Integer.class, "%" + keyword + "%");
        return count == null ? 0 : count;
    }

    /** 按 ID 查询用户并返回防御性副本。 */
    public User findById(String id) {
        if (id == null) return null;
        User cached = cache.get(id);
        if (cached == null) {
            List<User> rows = jdbcTemplate.query("SELECT * FROM t_user WHERE id = ?", rowMapper, id);
            if (rows.isEmpty()) return null;
            cached = rows.get(0);
            cache.put(id, cached);
        }
        return copy(cached);
    }

    /** 更新登录时间、IP 与浏览器信息。 */
    public void updateLoginInfo(String userId, String ip, String browser) {
        jdbcTemplate.update(
                "UPDATE t_user SET last_login_at = ?, last_login_ip = ?, last_login_browser = ? WHERE id = ?",
                LocalDateTime.now().format(TIME_FORMAT), ip, browser, userId);
        invalidate(userId);
    }

    /** 更新用户基础资料、权限与限制。 */
    public void update(User user) {
        jdbcTemplate.update(
                "UPDATE t_user SET username=?, password=?, role=?, created_at=?, last_login_at=?, last_login_ip=?, last_login_browser=?, display_name=?, email=?, phone=?, department=?, job_title=?, bio=?, avatar_type=?, avatar_value=?, allowed_model_ids=?, system_prompt=?, disabled=?, daily_limit_type=?, daily_limit_value=?, prompt_presets=? WHERE id=?",
                user.getUsername(), user.getPassword(), user.getRole(), user.getCreatedAt(), user.getLastLoginAt(),
                user.getLastLoginIp(), user.getLastLoginBrowser(), user.getDisplayName(), user.getEmail(), user.getPhone(),
                user.getDepartment(), user.getJobTitle(), user.getBio(), user.getAvatarType(), user.getAvatarValue(),
                toJson(user.getAllowedModelIds()), user.getSystemPrompt(), user.isDisabled() ? 1 : 0,
                user.getDailyLimitType(), user.getDailyLimitValue(), toJson(user.getPromptPresets()), user.getId());
        invalidate(user.getId());
    }

    /** 更新用户密码摘要。 */
    public void updatePassword(String userId, String passwordHash) {
        jdbcTemplate.update("UPDATE t_user SET password = ? WHERE id = ?", passwordHash, userId);
        invalidate(userId);
    }

    /** 级联删除用户持有的业务数据与用户记录。 */
    public boolean deleteCascade(String userId) {
        boolean deleted = Boolean.TRUE.equals(transactions.execute(status -> deleteUserRows(userId)));
        invalidate(userId);
        return deleted;
    }

    /** 在同一事务连接中删除用户关联行，供 Spring 和兼容构造器共同使用。 */
    private boolean deleteUserRows(String userId) {
        jdbcTemplate.update("DELETE FROM t_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_share WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_user_state WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_history WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_context_summary WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_file_asset_grant WHERE user_id = ?", userId);
        jdbcTemplate.update(
                "DELETE FROM t_file_asset_grant WHERE url IN (SELECT url FROM t_file_asset WHERE owner_user_id = ?)",
                userId);
        jdbcTemplate.update("DELETE FROM t_file_asset WHERE owner_user_id = ?", userId);
        boolean deleted = jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId) > 0;
        return deleted;
    }

    /** 启用双重验证。 */
    public boolean enableTwoFactor(String userId, String encryptedSecret, List<String> recoveryCodeHashes) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_enabled=1, two_factor_secret=?, recovery_code_hashes=?, two_factor_last_used_step=-1 WHERE id=? AND two_factor_enabled=0",
                encryptedSecret, toJson(recoveryCodeHashes), userId);
        invalidate(userId);
        return updated > 0;
    }

    /** 关闭双重验证并清除安全凭据。 */
    public boolean disableTwoFactor(String userId) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_enabled=0, two_factor_secret=NULL, recovery_code_hashes='[]', two_factor_last_used_step=-1 WHERE id=?",
                userId);
        invalidate(userId);
        return updated > 0;
    }

    /** 原子占用新的 TOTP 时间步。 */
    public boolean claimTwoFactorStep(String userId, long step) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_last_used_step=? WHERE id=? AND two_factor_enabled=1 AND two_factor_last_used_step < ?",
                step, userId, step);
        if (updated > 0) invalidate(userId);
        return updated > 0;
    }

    /** 原子消费一个恢复码摘要。 */
    public synchronized boolean consumeRecoveryCode(String userId, String recoveryCodeHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recovery_code_hashes FROM t_user WHERE id=? AND two_factor_enabled=1", userId);
        if (rows.isEmpty()) return false;
        List<String> hashes = parseStringList((String) rows.get(0).get("recovery_code_hashes"));
        if (!hashes.remove(recoveryCodeHash)) return false;
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET recovery_code_hashes=? WHERE id=? AND two_factor_enabled=1", toJson(hashes), userId);
        if (updated > 0) invalidate(userId);
        return updated > 0;
    }

    /** 整体替换双重验证恢复码摘要。 */
    public boolean replaceRecoveryCodes(String userId, List<String> recoveryCodeHashes) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET recovery_code_hashes=? WHERE id=? AND two_factor_enabled=1",
                toJson(recoveryCodeHashes), userId);
        if (updated > 0) invalidate(userId);
        return updated > 0;
    }

    /** 清空全部用户缓存。 */
    public void invalidateCache() {
        cache.clear();
    }

    /** 使指定用户缓存失效。 */
    private void invalidate(String userId) {
        if (userId != null) cache.remove(userId);
    }

    /** 创建用户对象的防御性深拷贝。 */
    private User copy(User source) {
        User copy = new User();
        copy.setId(source.getId()); copy.setUsername(source.getUsername()); copy.setPassword(source.getPassword());
        copy.setRole(source.getRole()); copy.setCreatedAt(source.getCreatedAt()); copy.setLastLoginAt(source.getLastLoginAt());
        copy.setLastLoginIp(source.getLastLoginIp()); copy.setLastLoginBrowser(source.getLastLoginBrowser());
        copy.setDisplayName(source.getDisplayName()); copy.setEmail(source.getEmail()); copy.setPhone(source.getPhone());
        copy.setDepartment(source.getDepartment()); copy.setJobTitle(source.getJobTitle()); copy.setBio(source.getBio());
        copy.setAvatarType(source.getAvatarType()); copy.setAvatarValue(source.getAvatarValue());
        copy.setAllowedModelIds(source.getAllowedModelIds() == null ? new ArrayList<>() : new ArrayList<>(source.getAllowedModelIds()));
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setPromptPresets(source.getPromptPresets() == null ? new ArrayList<>() : new ArrayList<>(source.getPromptPresets()));
        copy.setDisabled(source.isDisabled()); copy.setDailyLimitType(source.getDailyLimitType());
        copy.setDailyLimitValue(source.getDailyLimitValue()); copy.setTwoFactorEnabled(source.isTwoFactorEnabled());
        copy.setTwoFactorSecret(source.getTwoFactorSecret());
        copy.setRecoveryCodeHashes(source.getRecoveryCodeHashes() == null ? new ArrayList<>() : new ArrayList<>(source.getRecoveryCodeHashes()));
        copy.setTwoFactorLastUsedStep(source.getTwoFactorLastUsedStep());
        return copy;
    }

    /** 解析字符串数组 JSON。 */
    private List<String> parseStringList(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception exception) { return new ArrayList<>(); }
    }

    /** 解析用户提示词预设 JSON。 */
    private List<PromptPreset> parsePromptPresets(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<PromptPreset> presets = objectMapper.readValue(json, new TypeReference<List<PromptPreset>>() {});
            return presets == null ? new ArrayList<>() : presets;
        }
        catch (Exception exception) { return new ArrayList<>(); }
    }

    /** 将用户列表字段序列化为 JSON。 */
    private String toJson(Object value) {
        if (value == null) return "[]";
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { return "[]"; }
    }
}
