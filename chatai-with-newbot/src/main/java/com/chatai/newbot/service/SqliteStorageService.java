package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SQLite 存储实现 - 使用 JdbcTemplate 操作 SQLite 数据库
 * 数据库文件位于 data/chatai.db，WAL 模式，连接池 size=1。
 * 厂商列表（providers.json）为 classpath 只读资源，与 JSON 实现相同。
 * 厂商显示名覆盖、IP注册计数、默认模型等配置存储在 t_setting 键值表中。
 */
@Component
public class SqliteStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(SqliteStorageService.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 内置厂商列表（classpath 只读资源）
    private List<Provider> providers = Collections.synchronizedList(new ArrayList<>());
    // 厂商显示名覆盖（内存缓存，持久化到 t_setting）
    private Map<String, String> providerNameOverrides = new ConcurrentHashMap<>();
    // IP 注册计数（内存缓存，持久化到 t_setting）
    private Map<String, Map<String, Integer>> ipRegisterMap = new ConcurrentHashMap<>();

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    public SqliteStorageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 初始化：设置 SQLite PRAGMA、建表、加载只读数据
     */
    @PostConstruct
    public void init() {
        try {
            // 设置 WAL 模式和忙碌超时
            jdbcTemplate.execute("PRAGMA journal_mode=WAL");
            jdbcTemplate.execute("PRAGMA busy_timeout=5000");

            // 建表（IF NOT EXISTS，幂等）
            createTables();

            // 存量明文 API Key 一次性加密升级
            encryptLegacyApiKeys();

            // 加载 classpath 内置厂商
            loadProviders();
            log.info("SQLite: 已加载 {} 个内置厂商", providers.size());

            // 加载厂商显示名覆盖
            loadProviderNameOverrides();
            log.info("SQLite: 已加载 {} 个厂商显示名覆盖", providerNameOverrides.size());

            // 加载 IP 注册计数
            loadIpRegisterMap();

            // 确保 admin 用户存在
            ensureAdminUser();
            log.info("SQLite: 已确保admin用户存在");

            log.info("SQLite存储服务初始化完成");
        } catch (Exception e) {
            log.error("初始化SQLite存储服务失败", e);
            throw new RuntimeException("初始化SQLite存储服务失败", e);
        }
    }

    /**
     * 创建所有数据表（幂等操作）
     */
    private void createTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_user (" +
                "id TEXT PRIMARY KEY," +
                "username TEXT NOT NULL UNIQUE," +
                "password TEXT NOT NULL," +
                "role TEXT NOT NULL DEFAULT 'user'," +
                "created_at TEXT," +
                "last_login_at TEXT," +
                "last_login_ip TEXT," +
                "last_login_browser TEXT," +
                "allowed_model_ids TEXT DEFAULT '[]'," +
                "system_prompt TEXT," +
                "disabled INTEGER DEFAULT 0," +
                "daily_limit_type TEXT," +
                "daily_limit_value INTEGER DEFAULT 0," +
                "prompt_presets TEXT" +
                ")");

        // 老数据库补充 system_prompt 列（幂等迁移）
        ensureUserSystemPromptColumn();
        // 老数据库补充 disabled 列（幂等迁移）
        ensureUserDisabledColumn();
        // 老数据库补充单用户每日限额列（幂等迁移）
        ensureUserDailyLimitColumns();
        // 老数据库补充提示词预设列（幂等迁移）
        ensureUserPromptPresetsColumn();

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_model_config (" +
                "id TEXT PRIMARY KEY," +
                "provider_id TEXT," +
                "provider_name TEXT," +
                "provider_icon TEXT," +
                "model_id TEXT NOT NULL," +
                "display_name TEXT," +
                "api_key TEXT," +
                "api_url TEXT," +
                "protocol TEXT DEFAULT 'openai'," +
                "thinking_param_type TEXT DEFAULT 'default'," +
                "supports_thinking INTEGER DEFAULT 0," +
                "supports_multimodal INTEGER DEFAULT 0," +
                "enabled INTEGER DEFAULT 1," +
                "visible_to_all INTEGER DEFAULT 1," +
                "built_in INTEGER DEFAULT 0," +
                "created_at TEXT," +
                "test_latency_ms INTEGER," +
                "test_speed REAL," +
                "tested_at TEXT" +
                ")");

        // 老数据库补充连通测试指标列（幂等迁移）
        ensureModelTestColumns();

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_usage_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id TEXT," +
                "username TEXT," +
                "model_id TEXT," +
                "model_name TEXT," +
                "timestamp TEXT," +
                "prompt_tokens INTEGER DEFAULT 0," +
                "completion_tokens INTEGER DEFAULT 0," +
                "cached_tokens INTEGER DEFAULT 0," +
                "reasoning_tokens INTEGER DEFAULT 0," +
                "deep_thinking INTEGER DEFAULT 0" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_user ON t_usage_log(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_time ON t_usage_log(timestamp)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_history (" +
                "user_id TEXT PRIMARY KEY," +
                "chat_data TEXT NOT NULL," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0" +
                ")");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_setting (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT" +
                ")");

        // 登录 Token 持久化表（服务重启后登录态不丢失）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_token (" +
                "token TEXT PRIMARY KEY," +
                "user_id TEXT NOT NULL," +
                "ip TEXT," +
                "browser TEXT," +
                "created_at TEXT," +
                "expires_at INTEGER NOT NULL DEFAULT 0" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_token_user ON t_token(user_id)");
        // 老数据库补充 browser 列（幂等迁移）
        ensureTokenBrowserColumn();

        // 会话分享表（只读链接，两种存储模式共用 SQLite）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_share (" +
                "id TEXT PRIMARY KEY," +
                "chat_id TEXT NOT NULL," +
                "user_id TEXT NOT NULL," +
                "user_name TEXT," +
                "title TEXT," +
                "created_at TEXT," +
                "expires_at TEXT" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_user ON t_chat_share(user_id)");
        // 老数据库补充 expires_at 列（幂等迁移）
        ensureChatShareExpiresColumn();

        log.info("SQLite: 数据表已就绪");
    }

    /**
     * 为 t_user 表补充 system_prompt 列（幂等迁移）。
     * 老版本数据库没有该列时自动执行 ALTER TABLE；已存在则跳过，保证重复启动安全。
     */
    private void ensureUserSystemPromptColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_user)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "system_prompt".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN system_prompt TEXT");
            log.info("SQLite: t_user 表已补充 system_prompt 列");
        }
    }

    /**
     * 为 t_token 表补充 browser 列（幂等迁移）。
     * 记录登录时的浏览器/终端信息，用于个人设置中的登录设备管理。
     */
    private void ensureTokenBrowserColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_token)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "browser".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_token ADD COLUMN browser TEXT");
            log.info("SQLite: t_token 表已补充 browser 列");
        }
    }

    /**
     * 为 t_model_config 表补充连通测试指标列（幂等迁移）。
     * 记录管理员手动测试的延迟(ms)/生成速度(token/s)/测试时间。
     */
    private void ensureModelTestColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_model_config)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "test_latency_ms".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN test_latency_ms INTEGER");
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN test_speed REAL");
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN tested_at TEXT");
            log.info("SQLite: t_model_config 表已补充连通测试指标列");
        }
    }

    /**
     * 为 t_user 表补充 prompt_presets 列（幂等迁移）。
     * 存储用户自定义提示词预设列表的 JSON。
     */
    private void ensureUserPromptPresetsColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_user)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "prompt_presets".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN prompt_presets TEXT");
            log.info("SQLite: t_user 表已补充 prompt_presets 列");
        }
    }

    /** 解析提示词预设 JSON 字符串为列表（空或解析失败返回空列表） */
    private List<PromptPreset> parsePromptPresets(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<PromptPreset> list = objectMapper.readValue(json, new TypeReference<List<PromptPreset>>() {});
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析 prompt_presets 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 序列化提示词预设列表为 JSON 字符串（空列表存 '[]'） */
    private String toPromptPresetsJson(List<PromptPreset> presets) {
        try {
            return objectMapper.writeValueAsString(presets != null ? presets : new ArrayList<>());
        } catch (Exception e) {
            log.warn("序列化 prompt_presets 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 存量明文 API Key 一次性加密升级（幂等迁移）。
     * 扫描 t_model_config 中无 ENC: 前缀的明文 Key，加密后写回；已是密文则跳过。
     */
    private void encryptLegacyApiKeys() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, api_key FROM t_model_config WHERE api_key IS NOT NULL AND api_key != ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String apiKey = String.valueOf(row.get("api_key"));
            if (!ApiKeyCrypto.isEncrypted(apiKey)) {
                String encrypted = ApiKeyCrypto.encrypt(apiKey);
                if (ApiKeyCrypto.isEncrypted(encrypted)) {
                    jdbcTemplate.update("UPDATE t_model_config SET api_key = ? WHERE id = ?",
                            encrypted, row.get("id"));
                    migrated++;
                }
            }
        }
        if (migrated > 0) {
            log.info("SQLite: 已将 {} 个存量明文 API Key 加密存储", migrated);
        }
    }

    /**
     * 为 t_user 表补充 disabled 列（幂等迁移）。
     * 老版本数据库没有该列时自动执行 ALTER TABLE；已存在则跳过，保证重复启动安全。
     */
    private void ensureUserDisabledColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_user)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "disabled".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN disabled INTEGER DEFAULT 0");
            log.info("SQLite: t_user 表已补充 disabled 列");
        }
    }

    /**
     * 为 t_user 表补充单用户每日限额列（幂等迁移）。
     * 老版本数据库没有 daily_limit_type / daily_limit_value 列时自动执行 ALTER TABLE；已存在则跳过。
     */
    private void ensureUserDailyLimitColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_user)");
        boolean hasType = columns.stream()
                .anyMatch(c -> "daily_limit_type".equals(String.valueOf(c.get("name"))));
        if (!hasType) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN daily_limit_type TEXT");
            log.info("SQLite: t_user 表已补充 daily_limit_type 列");
        }
        boolean hasValue = columns.stream()
                .anyMatch(c -> "daily_limit_value".equals(String.valueOf(c.get("name"))));
        if (!hasValue) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN daily_limit_value INTEGER DEFAULT 0");
            log.info("SQLite: t_user 表已补充 daily_limit_value 列");
        }
    }

    /**
     * 为 t_chat_share 表补充 expires_at 列（幂等迁移）。
     */
    private void ensureChatShareExpiresColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_chat_share)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "expires_at".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_chat_share ADD COLUMN expires_at TEXT");
            log.info("SQLite: t_chat_share 表已补充 expires_at 列");
        }
    }

    // ========== t_setting 键值操作 ==========

    /**
     * 读取配置值
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    public String getSetting(String key) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT value FROM t_setting WHERE key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 写入配置值（存在则更新，不存在则插入）
     * @param key 配置键
     * @param value 配置值
     */
    public void setSetting(String key, String value) {
        int updated = jdbcTemplate.update("UPDATE t_setting SET value = ? WHERE key = ?", value, key);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO t_setting (key, value) VALUES (?, ?)", key, value);
        }
    }

    // ========== 用户相关 ==========

    /** 用户行映射器 */
    private final RowMapper<User> userRowMapper = (ResultSet rs, int rowNum) -> {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setRole(rs.getString("role"));
        u.setCreatedAt(rs.getString("created_at"));
        u.setLastLoginAt(rs.getString("last_login_at"));
        u.setLastLoginIp(rs.getString("last_login_ip"));
        u.setLastLoginBrowser(rs.getString("last_login_browser"));
        u.setAllowedModelIds(parseJsonArray(rs.getString("allowed_model_ids")));
        u.setSystemPrompt(rs.getString("system_prompt"));
        u.setPromptPresets(parsePromptPresets(rs.getString("prompt_presets")));
        u.setDisabled(rs.getInt("disabled") == 1);
        u.setDailyLimitType(rs.getString("daily_limit_type"));
        u.setDailyLimitValue(rs.getInt("daily_limit_value"));
        return u;
    };

    /** 确保内置 admin 用户存在 */
    private void ensureAdminUser() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, ADMIN_USERNAME);
        if (count == null || count == 0) {
            User admin = new User();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(PasswordHasher.hash(ADMIN_DEFAULT_PASSWORD));
            admin.setRole("admin");
            admin.setCreatedAt(nowString());
            insertUser(admin);
            log.info("SQLite: 已创建内置admin账户");
        }
    }

    /** 插入用户记录 */
    private void insertUser(User u) {
        jdbcTemplate.update(
                "INSERT INTO t_user (id, username, password, role, created_at, last_login_at, last_login_ip, last_login_browser, allowed_model_ids, system_prompt, disabled, daily_limit_type, daily_limit_value, prompt_presets) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                u.getId(), u.getUsername(), u.getPassword(), u.getRole(), u.getCreatedAt(),
                u.getLastLoginAt(), u.getLastLoginIp(), u.getLastLoginBrowser(),
                toJsonArray(u.getAllowedModelIds()), u.getSystemPrompt(), u.isDisabled() ? 1 : 0,
                u.getDailyLimitType(), u.getDailyLimitValue(), toPromptPresetsJson(u.getPromptPresets()));
    }

    @Override
    public User authenticate(String username, String password) {
        List<User> users = jdbcTemplate.query(
                "SELECT * FROM t_user WHERE username = ?", userRowMapper, username);
        User user = users.isEmpty() ? null : users.get(0);
        if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
            return null;
        }
        // 旧版 SHA-256 哈希校验通过后透明升级为 BCrypt
        if (PasswordHasher.isLegacyHash(user.getPassword())) {
            user.setPassword(PasswordHasher.hash(password));
            jdbcTemplate.update("UPDATE t_user SET password = ? WHERE id = ?",
                    user.getPassword(), user.getId());
        }
        return user;
    }

    @Override
    public boolean canRegisterFromIp(String ip) {
        String today = LocalDate.now().toString();
        Map<String, Integer> todayMap = ipRegisterMap.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        int count = todayMap.getOrDefault(ip, 0);
        return count < 5;
    }

    @Override
    public User register(String username, String password, String ip) {
        // 检查用户名是否已存在
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) return null;
        if (ADMIN_USERNAME.equalsIgnoreCase(username)) return null;

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPassword(PasswordHasher.hash(password));
        user.setRole("user");
        user.setCreatedAt(nowString());
        user.setLastLoginIp(ip);
        insertUser(user);

        // 更新 IP 注册计数
        String today = LocalDate.now().toString();
        Map<String, Integer> todayMap = ipRegisterMap.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        todayMap.merge(ip, 1, Integer::sum);
        saveIpRegisterMap();

        return user;
    }

    @Override
    public void updateLoginInfo(String userId, String ip, String browser) {
        jdbcTemplate.update(
                "UPDATE t_user SET last_login_at = ?, last_login_ip = ?, last_login_browser = ? WHERE id = ?",
                nowString(), ip, browser, userId);
    }

    @Override
    public List<User> getAllUsers() {
        return jdbcTemplate.query("SELECT * FROM t_user", userRowMapper);
    }

    @Override
    public User getUserById(String id) {
        List<User> users = jdbcTemplate.query(
                "SELECT * FROM t_user WHERE id = ?", userRowMapper, id);
        return users.isEmpty() ? null : users.get(0);
    }

    @Override
    public boolean deleteUser(String userId) {
        // admin 不可删除
        User user = getUserById(userId);
        if (user == null || "admin".equals(user.getRole())) return false;
        int deleted = jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        return deleted > 0;
    }

    @Override
    public void updateUser(User user) {
        jdbcTemplate.update(
                "UPDATE t_user SET username=?, password=?, role=?, created_at=?, last_login_at=?, last_login_ip=?, last_login_browser=?, allowed_model_ids=?, system_prompt=?, disabled=?, daily_limit_type=?, daily_limit_value=?, prompt_presets=? WHERE id=?",
                user.getUsername(), user.getPassword(), user.getRole(), user.getCreatedAt(),
                user.getLastLoginAt(), user.getLastLoginIp(), user.getLastLoginBrowser(),
                toJsonArray(user.getAllowedModelIds()), user.getSystemPrompt(), user.isDisabled() ? 1 : 0,
                user.getDailyLimitType(), user.getDailyLimitValue(), toPromptPresetsJson(user.getPromptPresets()), user.getId());
    }

    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) return 1;
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) return 2;
        jdbcTemplate.update("UPDATE t_user SET password = ? WHERE id = ?",
                PasswordHasher.hash(newPassword), userId);
        return 0;
    }

    // ========== 模型配置相关 ==========

    /** 模型配置行映射器 */
    private final RowMapper<ModelConfig> modelConfigRowMapper = (ResultSet rs, int rowNum) -> {
        ModelConfig m = new ModelConfig();
        m.setId(rs.getString("id"));
        m.setProviderId(rs.getString("provider_id"));
        m.setProviderName(rs.getString("provider_name"));
        m.setProviderIcon(rs.getString("provider_icon"));
        m.setModelId(rs.getString("model_id"));
        m.setDisplayName(rs.getString("display_name"));
        m.setApiKey(ApiKeyCrypto.decrypt(rs.getString("api_key")));
        m.setApiUrl(rs.getString("api_url"));
        m.setProtocol(rs.getString("protocol"));
        m.setThinkingParamType(rs.getString("thinking_param_type"));
        m.setSupportsThinking(rs.getInt("supports_thinking") == 1);
        m.setSupportsMultimodal(rs.getInt("supports_multimodal") == 1);
        m.setEnabled(rs.getInt("enabled") == 1);
        // visible_to_all: NULL 视为 true
        int vta = rs.getInt("visible_to_all");
        m.setVisibleToAll(rs.wasNull() ? true : vta == 1);
        m.setBuiltIn(rs.getInt("built_in") == 1);
        m.setCreatedAt(rs.getString("created_at"));
        // 连通测试指标（可空，null=未测试）
        int latency = rs.getInt("test_latency_ms");
        m.setTestLatencyMs(rs.wasNull() ? null : latency);
        double speed = rs.getDouble("test_speed");
        m.setTestSpeed(rs.wasNull() ? null : speed);
        m.setTestedAt(rs.getString("tested_at"));
        return m;
    };

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        return jdbcTemplate.query("SELECT * FROM t_model_config", modelConfigRowMapper);
    }

    @Override
    public List<ModelConfig> getVisibleModels(User user) {
        // 权限语义：admin 全部可见；配置了 allowedModelIds（非空）的用户仅可见白名单内模型；
        // 未配置则不限制，可见所有公开（visibleToAll）模型
        return getAllModelConfigs().stream()
                .filter(ModelConfig::isEnabled)
                .filter(m -> isModelPermitted(user, m))
                .collect(Collectors.toList());
    }

    private boolean isModelPermitted(User user, ModelConfig m) {
        if (user.isAdmin()) return true;
        List<String> allowed = user.getAllowedModelIds();
        if (allowed != null && !allowed.isEmpty()) {
            return allowed.contains(m.getId());
        }
        return Boolean.TRUE.equals(m.getVisibleToAll());
    }

    @Override
    public ModelConfig getModelConfigById(String id) {
        List<ModelConfig> list = jdbcTemplate.query(
                "SELECT * FROM t_model_config WHERE id = ?", modelConfigRowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public ModelConfig addModelConfig(ModelConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(nowString());
        }
        if (config.getVisibleToAll() == null) {
            config.setVisibleToAll(true);
        }
        fillProviderInfo(config);
        insertModelConfig(config);
        return config;
    }

    /** 插入模型配置记录 */
    private void insertModelConfig(ModelConfig m) {
        jdbcTemplate.update(
                "INSERT INTO t_model_config (id, provider_id, provider_name, provider_icon, model_id, display_name, api_key, api_url, protocol, thinking_param_type, supports_thinking, supports_multimodal, enabled, visible_to_all, built_in, created_at, test_latency_ms, test_speed, tested_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.getId(), m.getProviderId(), m.getProviderName(), m.getProviderIcon(),
                m.getModelId(), m.getDisplayName(), ApiKeyCrypto.encrypt(m.getApiKey()), m.getApiUrl(),
                m.getProtocol(), m.getThinkingParamType(),
                m.isSupportsThinking() ? 1 : 0, m.isSupportsMultimodal() ? 1 : 0,
                m.isEnabled() ? 1 : 0,
                m.getVisibleToAll() == null ? 1 : (m.getVisibleToAll() ? 1 : 0),
                m.isBuiltIn() ? 1 : 0, m.getCreatedAt(),
                m.getTestLatencyMs(), m.getTestSpeed(), m.getTestedAt());
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        fillProviderInfo(config);
        jdbcTemplate.update(
                "UPDATE t_model_config SET provider_id=?, provider_name=?, provider_icon=?, model_id=?, display_name=?, api_key=?, api_url=?, protocol=?, thinking_param_type=?, supports_thinking=?, supports_multimodal=?, enabled=?, visible_to_all=?, built_in=?, created_at=?, test_latency_ms=?, test_speed=?, tested_at=? WHERE id=?",
                config.getProviderId(), config.getProviderName(), config.getProviderIcon(),
                config.getModelId(), config.getDisplayName(), ApiKeyCrypto.encrypt(config.getApiKey()), config.getApiUrl(),
                config.getProtocol(), config.getThinkingParamType(),
                config.isSupportsThinking() ? 1 : 0, config.isSupportsMultimodal() ? 1 : 0,
                config.isEnabled() ? 1 : 0,
                config.getVisibleToAll() == null ? 1 : (config.getVisibleToAll() ? 1 : 0),
                config.isBuiltIn() ? 1 : 0, config.getCreatedAt(),
                config.getTestLatencyMs(), config.getTestSpeed(), config.getTestedAt(),
                config.getId());
    }

    @Override
    public boolean deleteModelConfig(String id) {
        int deleted = jdbcTemplate.update("DELETE FROM t_model_config WHERE id = ?", id);
        if (deleted > 0) {
            // 删除的是默认模型则清空默认设置
            String defId = getDefaultModelId();
            if (id.equals(defId)) {
                clearDefaultModelId();
            }
        }
        return deleted > 0;
    }

    /** 自动填充厂商信息 */
    private void fillProviderInfo(ModelConfig config) {
        if (config.getProviderId() != null && !"custom".equals(config.getProviderId())
                && !"__custom__".equals(config.getProviderId())) {
            providers.stream()
                    .filter(p -> p.getId().equals(config.getProviderId()))
                    .findFirst()
                    .ifPresent(p -> {
                        String displayName = getProviderDisplayName(p.getId());
                        if (config.getProviderName() == null || config.getProviderName().isEmpty()) {
                            config.setProviderName(displayName);
                        }
                        if (config.getProviderIcon() == null || config.getProviderIcon().isEmpty()) {
                            config.setProviderIcon(p.getIcon());
                        }
                        if (config.getThinkingParamType() == null || config.getThinkingParamType().isEmpty()) {
                            config.setThinkingParamType(p.getThinkingParamType());
                        }
                    });
        }
    }

    // ========== 默认模型 ==========

    @Override
    public String getDefaultModelId() {
        String val = getSetting("default_model_id");
        return (val == null || val.isEmpty()) ? null : val;
    }

    @Override
    public synchronized void setDefaultModelId(String modelId) {
        String val = (modelId == null || modelId.isEmpty()) ? null : modelId;
        setSetting("default_model_id", val);
    }

    @Override
    public synchronized void clearDefaultModelId() {
        setSetting("default_model_id", null);
    }

    // ========== 厂商相关 ==========

    /** 从 classpath 加载内置厂商 providers.json */
    private void loadProviders() {
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("providers.json");
            if (is != null) {
                providers = Collections.synchronizedList(
                        objectMapper.readValue(is, new TypeReference<List<Provider>>() {}));
            } else {
                log.warn("SQLite: 未找到 providers.json");
                providers = Collections.synchronizedList(new ArrayList<>());
            }
        } catch (Exception e) {
            log.error("SQLite: 加载厂商配置失败", e);
            providers = Collections.synchronizedList(new ArrayList<>());
        }
    }

    @Override
    public List<Provider> getAllProviders() {
        List<Provider> copy = new ArrayList<>();
        for (Provider p : providers) {
            Provider c = new Provider();
            c.setId(p.getId());
            c.setName(getProviderDisplayName(p.getId()));
            c.setIcon(p.getIcon());
            c.setDefaultApiUrl(p.getDefaultApiUrl());
            c.setProtocol(p.getProtocol());
            c.setThinkingParamType(p.getThinkingParamType());
            c.setModels(p.getModels());
            copy.add(c);
        }
        return copy;
    }

    @Override
    public Provider getProvider(String providerId) {
        if (providerId == null) return null;
        return providers.stream().filter(p -> p.getId().equals(providerId)).findFirst().orElse(null);
    }

    @Override
    public String getProviderDisplayName(String providerId) {
        if (providerId == null) return null;
        Provider p = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
        if (p == null) return null;
        String override = providerNameOverrides.get(providerId);
        return (override != null && !override.trim().isEmpty()) ? override : p.getName();
    }

    @Override
    public synchronized int renameProvider(String providerId, String newName, String newIcon, String oldName) {
        if (providerId == null || newName == null) {
            throw new IllegalArgumentException("providerId 和 newName 不能为空");
        }
        String trimmed = newName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("厂商名称不能为空");
        }
        String trimmedIcon = newIcon == null ? "" : newIcon.trim();
        if (providerId.startsWith("__custom__")) {
            String targetOldName = oldName == null ? trimmed : oldName;
            List<ModelConfig> configs = getAllModelConfigs();
            int updated = 0;
            for (ModelConfig c : configs) {
                if ("__custom__".equals(c.getProviderId()) && targetOldName.equals(c.getProviderName())) {
                    boolean nameChanged = !trimmed.equals(c.getProviderName());
                    boolean iconChanged = !trimmedIcon.isEmpty() && !trimmedIcon.equals(c.getProviderIcon());
                    if (nameChanged) c.setProviderName(trimmed);
                    if (iconChanged) c.setProviderIcon(trimmedIcon);
                    if (nameChanged || iconChanged) {
                        updateModelConfig(c);
                        updated++;
                    }
                }
            }
            return updated;
        }
        Provider p = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
        if (p == null) throw new IllegalArgumentException("厂商不存在: " + providerId);
        if (trimmed.equals(p.getName())) {
            providerNameOverrides.remove(providerId);
        } else {
            providerNameOverrides.put(providerId, trimmed);
        }
        saveProviderNameOverrides();
        // 同步所有 ModelConfig 的厂商名
        List<ModelConfig> configs = getAllModelConfigs();
        int updated = 0;
        for (ModelConfig c : configs) {
            if (providerId.equals(c.getProviderId()) && !trimmed.equals(c.getProviderName())) {
                c.setProviderName(trimmed);
                updateModelConfig(c);
                updated++;
            }
        }
        return updated;
    }

    @Override
    public List<Map<String, Object>> listCustomProviders() {
        List<ModelConfig> configs = getAllModelConfigs();
        Map<String, int[]> nameCount = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> nameIconCount = new LinkedHashMap<>();
        for (ModelConfig c : configs) {
            if ("__custom__".equals(c.getProviderId())) {
                String n = c.getProviderName();
                if (n == null || n.trim().isEmpty()) continue;
                nameCount.computeIfAbsent(n, k -> new int[]{0})[0]++;
                String ic = c.getProviderIcon();
                if (ic == null || ic.trim().isEmpty()) continue;
                Map<String, Integer> iconMap = nameIconCount.computeIfAbsent(n, k -> new LinkedHashMap<>());
                iconMap.merge(ic, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        nameCount.forEach((name, count) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "__custom__");
            m.put("name", name);
            m.put("modelCount", count[0]);
            String mainIcon = "";
            int maxCount = 0;
            Map<String, Integer> iconMap = nameIconCount.get(name);
            if (iconMap != null) {
                for (Map.Entry<String, Integer> e : iconMap.entrySet()) {
                    if (e.getValue() > maxCount) {
                        maxCount = e.getValue();
                        mainIcon = e.getKey();
                    }
                }
            }
            m.put("icon", mainIcon);
            m.put("type", "custom");
            result.add(m);
        });
        return result;
    }

    // ========== 厂商显示名覆盖（持久化到 t_setting） ==========

    /** 从 t_setting 加载厂商显示名覆盖 */
    private void loadProviderNameOverrides() {
        String json = getSetting("provider_name_overrides");
        if (json != null && !json.isEmpty()) {
            try {
                Map<String, String> loaded = objectMapper.readValue(json,
                        new TypeReference<Map<String, String>>() {});
                if (loaded != null) {
                    providerNameOverrides.clear();
                    providerNameOverrides.putAll(loaded);
                }
            } catch (Exception e) {
                log.error("SQLite: 加载厂商显示名覆盖失败", e);
            }
        }
    }

    /** 保存厂商显示名覆盖到 t_setting */
    private void saveProviderNameOverrides() {
        try {
            setSetting("provider_name_overrides", objectMapper.writeValueAsString(providerNameOverrides));
        } catch (Exception e) {
            log.error("SQLite: 保存厂商显示名覆盖失败", e);
        }
    }

    // ========== IP 注册计数（持久化到 t_setting） ==========

    /** 从 t_setting 加载 IP 注册计数 */
    private void loadIpRegisterMap() {
        String json = getSetting("ip_register");
        if (json != null && !json.isEmpty()) {
            try {
                Map<String, Map<String, Integer>> loaded = objectMapper.readValue(json,
                        new TypeReference<ConcurrentHashMap<String, Map<String, Integer>>>() {});
                if (loaded != null) {
                    ipRegisterMap.clear();
                    ipRegisterMap.putAll(loaded);
                }
            } catch (Exception e) {
                log.error("SQLite: 加载IP注册计数失败", e);
            }
        }
    }

    /** 保存 IP 注册计数到 t_setting */
    private void saveIpRegisterMap() {
        try {
            setSetting("ip_register", objectMapper.writeValueAsString(ipRegisterMap));
        } catch (Exception e) {
            log.error("SQLite: 保存IP注册计数失败", e);
        }
    }

    // ========== 使用记录相关 ==========

    /** 使用记录行映射器 */
    private final RowMapper<UsageLog> usageLogRowMapper = (ResultSet rs, int rowNum) -> {
        UsageLog l = new UsageLog();
        l.setUserId(rs.getString("user_id"));
        l.setUsername(rs.getString("username"));
        l.setModelId(rs.getString("model_id"));
        l.setModelName(rs.getString("model_name"));
        l.setTimestamp(rs.getString("timestamp"));
        l.setPromptTokens(rs.getInt("prompt_tokens"));
        l.setCompletionTokens(rs.getInt("completion_tokens"));
        l.setCachedTokens(rs.getInt("cached_tokens"));
        l.setReasoningTokens(rs.getInt("reasoning_tokens"));
        l.setDeepThinking(rs.getInt("deep_thinking") == 1);
        return l;
    };

    @Override
    public void addUsageLog(UsageLog logEntry) {
        jdbcTemplate.update(
                "INSERT INTO t_usage_log (user_id, username, model_id, model_name, timestamp, prompt_tokens, completion_tokens, cached_tokens, reasoning_tokens, deep_thinking) VALUES (?,?,?,?,?,?,?,?,?,?)",
                logEntry.getUserId(), logEntry.getUsername(), logEntry.getModelId(),
                logEntry.getModelName(), logEntry.getTimestamp(),
                logEntry.getPromptTokens(), logEntry.getCompletionTokens(),
                logEntry.getCachedTokens(), logEntry.getReasoningTokens(),
                logEntry.isDeepThinking() ? 1 : 0);
    }

    @Override
    public List<UsageLog> getAllUsageLogs() {
        return jdbcTemplate.query("SELECT * FROM t_usage_log", usageLogRowMapper);
    }

    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log WHERE user_id = ?", usageLogRowMapper, userId);
    }

    @Override
    public int countUsageByUserAndDay(String userId, String day) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log WHERE user_id = ? AND timestamp LIKE ?",
                Integer.class, userId, day + "%");
        return count == null ? 0 : count;
    }

    @Override
    public long sumTokensByUserAndDay(String userId, String day) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM t_usage_log WHERE user_id = ? AND timestamp LIKE ?",
                Long.class, userId, day + "%");
        return sum == null ? 0L : sum;
    }

    @Override
    public void updateUsageLog(UsageLog updatedLog) {
        // 按 userId + timestamp + modelId 匹配更新
        jdbcTemplate.update(
                "UPDATE t_usage_log SET prompt_tokens=?, completion_tokens=?, cached_tokens=?, reasoning_tokens=?, deep_thinking=? WHERE user_id=? AND timestamp=? AND model_id=?",
                updatedLog.getPromptTokens(), updatedLog.getCompletionTokens(),
                updatedLog.getCachedTokens(), updatedLog.getReasoningTokens(),
                updatedLog.isDeepThinking() ? 1 : 0,
                updatedLog.getUserId(), updatedLog.getTimestamp(), updatedLog.getModelId());
    }

    @Override
    public List<String> getUsageLogDates() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT SUBSTR(timestamp, 1, 10) AS day FROM t_usage_log ORDER BY day",
                String.class);
    }

    // ========== 聊天记录（整文档存储） ==========

    /**
     * 加载用户聊天记录 JSON 文档
     * @param userId 用户ID
     * @return JSON 字符串，不存在返回 null
     */
    public String loadChatData(String userId) {
        List<String> list = jdbcTemplate.queryForList(
                "SELECT chat_data FROM t_chat_history WHERE user_id = ?", String.class, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 保存用户聊天记录 JSON 文档（存在则更新，不存在则插入）
     * @param userId 用户ID
     * @param chatData JSON 字符串
     * @param updatedAt 更新时间字符串
     * @param updatedAtTs 更新时间戳
     */
    public void saveChatData(String userId, String chatData, String updatedAt, long updatedAtTs) {
        int updated = jdbcTemplate.update(
                "UPDATE t_chat_history SET chat_data=?, updated_at=?, updated_at_ts=? WHERE user_id=?",
                chatData, updatedAt, updatedAtTs, userId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO t_chat_history (user_id, chat_data, updated_at, updated_at_ts) VALUES (?,?,?,?)",
                    userId, chatData, updatedAt, updatedAtTs);
        }
    }

    /**
     * 删除用户聊天记录
     * @param userId 用户ID
     */
    public void deleteChatData(String userId) {
        jdbcTemplate.update("DELETE FROM t_chat_history WHERE user_id = ?", userId);
    }

    // ========== 登录 Token 持久化 ==========

    /**
     * 插入登录 Token 记录
     * @param token token 字符串
     * @param userId 用户ID
     * @param ip 登录IP
     * @param browser 登录浏览器/终端
     * @param expiresAt 过期时间戳（毫秒）
     */
    public void insertToken(String token, String userId, String ip, String browser, long expiresAt) {
        jdbcTemplate.update(
                "INSERT OR REPLACE INTO t_token (token, user_id, ip, browser, created_at, expires_at) VALUES (?,?,?,?,?,?)",
                token, userId, ip, browser, nowString(), expiresAt);
    }

    /**
     * 查询指定用户的所有登录 Token 记录（登录设备管理，新登录在前）
     * @param userId 用户ID
     * @return 每条记录含 token/ip/browser/created_at/expires_at
     */
    public List<Map<String, Object>> listTokensByUser(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT token, ip, browser, created_at, expires_at FROM t_token WHERE user_id = ? ORDER BY created_at DESC",
                userId);
    }

    /**
     * 删除指定 Token
     * @param token token 字符串
     */
    public void deleteToken(String token) {
        jdbcTemplate.update("DELETE FROM t_token WHERE token = ?", token);
    }

    /**
     * 删除指定用户的所有 Token
     * @param userId 用户ID
     */
    public void deleteTokensByUser(String userId) {
        jdbcTemplate.update("DELETE FROM t_token WHERE user_id = ?", userId);
    }

    /**
     * 更新 Token 过期时间（滑动续期）
     * @param token token 字符串
     * @param expiresAt 新的过期时间戳（毫秒）
     */
    public void updateTokenExpiry(String token, long expiresAt) {
        jdbcTemplate.update("UPDATE t_token SET expires_at = ? WHERE token = ?", expiresAt, token);
    }

    /**
     * 回填 Token 的浏览器信息（browser 列上线前登录的旧 token 值为空，
     * 当前设备访问登录管理时用本次请求的 UA 补齐）
     * @param token token 字符串
     * @param browser 浏览器/终端名称
     */
    public void updateTokenBrowser(String token, String browser) {
        jdbcTemplate.update("UPDATE t_token SET browser = ? WHERE token = ?", browser, token);
    }

    /**
     * 删除所有已过期的 Token
     * @param now 当前时间戳（毫秒）
     * @return 删除条数
     */
    public int deleteExpiredTokens(long now) {
        return jdbcTemplate.update("DELETE FROM t_token WHERE expires_at < ?", now);
    }

    /**
     * 加载所有未过期的 Token 记录（启动时恢复登录态）
     * @param now 当前时间戳（毫秒）
     * @return 每条记录含 token/user_id/ip/expires_at
     */
    public List<Map<String, Object>> loadActiveTokens(long now) {
        return jdbcTemplate.queryForList(
                "SELECT token, user_id, ip, expires_at FROM t_token WHERE expires_at >= ?", now);
    }

    // ========== 会话分享（恒走 SQLite，与存储模式开关无关） ==========

    /** 会话分享行映射器 */
    private final RowMapper<ChatShare> chatShareRowMapper = (ResultSet rs, int rowNum) -> {
        ChatShare s = new ChatShare();
        s.setId(rs.getString("id"));
        s.setChatId(rs.getString("chat_id"));
        s.setUserId(rs.getString("user_id"));
        s.setUserName(rs.getString("user_name"));
        s.setTitle(rs.getString("title"));
        s.setCreatedAt(rs.getString("created_at"));
        s.setExpiresAt(rs.getString("expires_at"));
        return s;
    };

    /**
     * 新增分享记录（自动生成分享码与创建时间）
     * @param s 分享记录
     * @return 保存后的记录
     */
    public ChatShare addChatShare(ChatShare s) {
        if (s.getId() == null || s.getId().isEmpty()) {
            s.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (s.getCreatedAt() == null) {
            s.setCreatedAt(nowString());
        }
        jdbcTemplate.update(
                "INSERT INTO t_chat_share (id, chat_id, user_id, user_name, title, created_at, expires_at) VALUES (?,?,?,?,?,?,?)",
                s.getId(), s.getChatId(), s.getUserId(), s.getUserName(), s.getTitle(), s.getCreatedAt(), s.getExpiresAt());
        return s;
    }

    /**
     * 根据分享码获取分享记录
     * @param id 分享码
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareById(String id) {
        List<ChatShare> list = jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE id = ?", chatShareRowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 获取用户创建的所有分享记录（新建在前）
     * @param userId 用户ID
     * @return 分享列表
     */
    public List<ChatShare> getChatSharesByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE user_id = ? ORDER BY created_at DESC",
                chatShareRowMapper, userId);
    }

    /**
     * 查找某用户对某会话已有的分享记录（同一会话复用分享码，避免重复生成）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareByChat(String userId, String chatId) {
        List<ChatShare> list = jdbcTemplate.query(
                "SELECT * FROM t_chat_share WHERE user_id = ? AND chat_id = ?",
                chatShareRowMapper, userId, chatId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除分享记录（撤销只读链接）
     * @param id 分享码
     * @return true=删除成功
     */
    public boolean deleteChatShare(String id) {
        return jdbcTemplate.update("DELETE FROM t_chat_share WHERE id = ?", id) > 0;
    }

    /**
     * 更新分享记录的过期时间（重新分享时续期；null=永久有效）
     * @param id 分享码
     * @param expiresAt 新的过期时间，null=永久
     */
    public void updateChatShareExpiry(String id, String expiresAt) {
        jdbcTemplate.update("UPDATE t_chat_share SET expires_at = ? WHERE id = ?", expiresAt, id);
    }

    // ========== 数据迁移辅助方法（供 StorageManager 调用） ==========

    /**
     * 批量插入用户（迁移用）
     * @param userList 用户列表
     */
    public void batchInsertUsers(List<User> userList) {
        for (User u : userList) {
            // 跳过已存在的（同时检查 id 和 username，避免 UNIQUE 约束冲突）
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_user WHERE id = ? OR username = ?",
                    Integer.class, u.getId(), u.getUsername());
            if (count != null && count > 0) continue;
            insertUser(u);
        }
    }

    /**
     * 批量插入模型配置（迁移用）
     * @param configList 模型配置列表
     */
    public void batchInsertModelConfigs(List<ModelConfig> configList) {
        for (ModelConfig m : configList) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_model_config WHERE id = ?", Integer.class, m.getId());
            if (count != null && count > 0) continue;
            insertModelConfig(m);
        }
    }

    /**
     * 批量插入使用记录（迁移用，幂等：按 user_id+timestamp+model_id 去重）
     * @param logList 使用记录列表
     */
    public void batchInsertUsageLogs(List<UsageLog> logList) {
        for (UsageLog l : logList) {
            // 跳过已存在的记录（避免重复迁移时产生重复数据）
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_usage_log WHERE user_id = ? AND timestamp = ? AND model_id = ?",
                    Integer.class, l.getUserId(), l.getTimestamp(), l.getModelId());
            if (count != null && count > 0) continue;
            addUsageLog(l);
        }
    }

    // ========== JSON 序列化工具 ==========

    /** 将 List<String> 序列化为 JSON 数组字符串 */
    private String toJsonArray(List<String> list) {
        if (list == null) return "[]";
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 将 JSON 数组字符串反序列化为 List<String> */
    private List<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 获取当前时间字符串 yyyy-MM-dd HH:mm:ss */
    private String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
