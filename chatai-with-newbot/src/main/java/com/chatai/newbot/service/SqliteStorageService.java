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

    // ========== 内存缓存（读多写少的小数据，减少高频 SQL 查询） ==========
    // t_setting 全量写穿缓存：启动时全量加载，读走缓存、写同步更新库+缓存（值为 NULL 的键不入缓存，读取同样返回 null）
    private final Map<String, String> settingsCache = new ConcurrentHashMap<>();
    // 模型配置列表失效式缓存：任何模型写操作后置空，下次读取重建（volatile 保证多线程可见性）
    private volatile List<ModelConfig> modelConfigsCache;
    // 当前启用公告缓存：null=未加载，Optional.empty=确认无启用公告；公告写操作后置空
    private volatile Optional<Announcement> enabledAnnouncementCache;
    // 用户对象失效式缓存（userId -> User）：认证拦截器每次请求都会按 token 查用户，
    // 缓存避免每个 API 请求都产生一次 SELECT t_user；任何用户写操作后失效对应条目。
    // getUserById 对外返回防御性副本，调用方修改不会污染缓存对象
    private final Map<String, User> userCache = new ConcurrentHashMap<>();

    private static final String ADMIN_USERNAME = "admin";
    /** 首次安装内置 admin 的兜底密码：仅当未通过环境变量/系统属性指定时使用的回退值。
     *  优先读取 CHATAI_ADMIN_PASSWORD 环境变量或 chatai.admin.password 系统属性（部署时显式指定），
     *  都未配置时使用本兜底值并输出醒目告警，提示管理员立即修改 */
    private static final String ADMIN_FALLBACK_PASSWORD = "admin123";
    /** 使用记录全量兜底查询的安全上限条数（主路径均为分页查询，此处仅防内存溢出） */
    private static final int USAGE_LOG_FETCH_LIMIT = 10000;

    public SqliteStorageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 初始化：设置 SQLite PRAGMA、建表、加载只读数据
     */
    @PostConstruct
    public void init() {
        try {
            // WAL 模式/busy_timeout/synchronous 由数据源连接参数统一下发（application.yml 的 hikari.data-source-properties），
            // 此处再执行一次 journal_mode=WAL 作为兜底（数据库级持久属性，幂等）
            jdbcTemplate.execute("PRAGMA journal_mode=WAL");

            // 建表（IF NOT EXISTS，幂等）
            createTables();

            // 全量加载 t_setting 到内存缓存（后续 getSetting 纯内存读取）
            loadSettingsCache();

            // 存量明文 API Key 一次性加密升级
            encryptLegacyApiKeys();

            // 加载 classpath 内置厂商
            loadProviders();
            log.info("SQLite: 已加载 {} 个内置厂商", providers.size());

            // 将历史固定 __custom__/custom 厂商迁移为稳定且唯一的厂商 ID。
            migrateLegacyCustomProviders();

            // 加载厂商显示名覆盖
            loadProviderNameOverrides();
            log.info("SQLite: 已加载 {} 个厂商显示名覆盖", providerNameOverrides.size());

            // 加载 IP 注册计数
            loadIpRegisterMap();

            // 确保 admin 用户存在
            ensureAdminUser();
            log.info("SQLite: 已确保admin用户存在");

            // 预置模型播种（全新库首次启动时写入各厂商旗舰模型待启用条目）
            seedDefaultModelConfigs();

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
                "display_name TEXT," +
                "email TEXT," +
                "phone TEXT," +
                "department TEXT," +
                "job_title TEXT," +
                "bio TEXT," +
                "avatar_type TEXT DEFAULT 'default'," +
                "avatar_value TEXT," +
                "allowed_model_ids TEXT DEFAULT '[]'," +
                "system_prompt TEXT," +
                "disabled INTEGER DEFAULT 0," +
                "daily_limit_type TEXT," +
                "daily_limit_value INTEGER DEFAULT 0," +
                "prompt_presets TEXT," +
                "two_factor_enabled INTEGER DEFAULT 0," +
                "two_factor_secret TEXT," +
                "recovery_code_hashes TEXT DEFAULT '[]'," +
                "two_factor_last_used_step INTEGER DEFAULT -1" +
                ")");

        // 老数据库补充 system_prompt 列（幂等迁移）
        ensureUserSystemPromptColumn();
        // 老数据库补充 disabled 列（幂等迁移）
        ensureUserDisabledColumn();
        // 老数据库补充单用户每日限额列（幂等迁移）
        ensureUserDailyLimitColumns();
        // 老数据库补充提示词预设列（幂等迁移）
        ensureUserPromptPresetsColumn();
        // 老数据库补充双重验证列（幂等迁移）
        ensureUserTwoFactorColumns();
        // 老数据库补充用户资料与头像列（幂等迁移）
        ensureUserProfileColumns();

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
                "health_check_enabled INTEGER DEFAULT 1," +
                "built_in INTEGER DEFAULT 0," +
                "created_at TEXT," +
                "test_latency_ms INTEGER," +
                "test_speed REAL," +
                "tested_at TEXT," +
                "input_price_cny REAL DEFAULT 0," +
                "output_price_cny REAL DEFAULT 0," +
                "cached_price_cny REAL DEFAULT 0," +
                "reasoning_price_cny REAL DEFAULT 0" +
                ")");

        // 老数据库补充连通测试指标列（幂等迁移）
        ensureModelTestColumns();
        // 老数据库补充人民币计费单价列（幂等迁移）
        ensureModelPricingColumns();
        // 老数据库补充健康检查开关列（幂等迁移，默认参与检查）
        ensureColumn("t_model_config", "health_check_enabled", "INTEGER DEFAULT 1");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_usage_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "request_id TEXT," +
                "user_id TEXT," +
                "username TEXT," +
                "model_id TEXT," +
                "model_name TEXT," +
                "timestamp TEXT," +
                "prompt_tokens INTEGER DEFAULT 0," +
                "completion_tokens INTEGER DEFAULT 0," +
                "cached_tokens INTEGER DEFAULT 0," +
                "reasoning_tokens INTEGER DEFAULT 0," +
                "deep_thinking INTEGER DEFAULT 0," +
                "cost_cny REAL" +
                ")");
        // 老数据库补充人民币成本快照列（幂等迁移）
        ensureUsageCostColumn();
        ensureColumn("t_usage_log", "request_id", "TEXT");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_request ON t_usage_log(request_id) WHERE request_id IS NOT NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_user ON t_usage_log(user_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_time ON t_usage_log(timestamp)");
        // 复合索引：个人配额统计（user_id + timestamp 范围/前缀查询）与用户维度时间筛选
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_user_time ON t_usage_log(user_id, timestamp)");
        // 单列筛选索引：后台使用记录按用户名/模型名下拉筛选（buildUsageWhere 的等值条件）
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_username ON t_usage_log(username)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_usage_model_name ON t_usage_log(model_name)");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_history (" +
                "user_id TEXT PRIMARY KEY," +
                "chat_data TEXT NOT NULL," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0" +
                ")");

        // 按会话行存储表（每行一个会话，保存时仅重写变更会话，避免整文档重写的写放大；
        // 冗余摘要列供侧边栏列表直查，无需解析消息 JSON；旧 t_chat_history 整文档保留作迁移来源与备份）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_session (" +
                "user_id TEXT NOT NULL," +
                "chat_id TEXT NOT NULL," +
                "messages TEXT NOT NULL," +
                "meta TEXT," +
                "title TEXT," +
                "preview TEXT," +
                "last_time TEXT," +
                "msg_count INTEGER DEFAULT 0," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0," +
                "PRIMARY KEY (user_id, chat_id)" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_user ON t_chat_session(user_id)");

        // 用户会话全局状态（最后所在会话 + 已删除会话ID累积 + 会话文件夹定义；行存在即表示该用户已完成按会话行迁移）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_user_state (" +
                "user_id TEXT PRIMARY KEY," +
                "last_chat_id TEXT," +
                "deleted_chat_ids TEXT," +
                "folders_json TEXT," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0" +
                ")");
        // 老数据库补充会话文件夹列（幂等迁移）
        ensureChatUserStateFoldersColumn();

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
                "expires_at TEXT," +
                "snapshot_json TEXT," +
                "password_hash TEXT," +
                "access_count INTEGER DEFAULT 0," +
                "max_views INTEGER DEFAULT 0," +
                "sanitized INTEGER DEFAULT 0" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_share_user ON t_chat_share(user_id)");
        // 老数据库补充 expires_at 列（幂等迁移）
        ensureChatShareExpiresColumn();
        ensureChatShareEnhancementColumns();

        // 自定义厂商独立持久化，ID 不再复用固定的 __custom__。
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_custom_provider (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL UNIQUE," +
                "icon TEXT," +
                "api_url TEXT," +
                "protocol TEXT DEFAULT 'openai'," +
                "thinking_param_type TEXT DEFAULT 'default'," +
                "models_json TEXT DEFAULT '[]'," +
                "created_at TEXT," +
                "updated_at TEXT" +
                ")");

        // 新上传资源记录所有者；存量资源无记录时按兼容策略处理。
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_file_asset (" +
                "url TEXT PRIMARY KEY," +
                "owner_user_id TEXT NOT NULL," +
                "asset_type TEXT NOT NULL," +
                "created_at TEXT" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_file_asset_owner ON t_file_asset(owner_user_id)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_file_asset_grant (" +
                "url TEXT NOT NULL," +
                "user_id TEXT NOT NULL," +
                "created_at TEXT," +
                "PRIMARY KEY(url,user_id)" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_file_asset_grant_user ON t_file_asset_grant(user_id)");

        // 系统公告表（支持公告期与历史公告，两种存储模式共用 SQLite）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_announcement (" +
                "id TEXT PRIMARY KEY," +
                "title TEXT," +
                "content TEXT NOT NULL," +
                "start_at TEXT," +
                "end_at TEXT," +
                "enabled INTEGER DEFAULT 1," +
                "created_at TEXT," +
                "updated_at TEXT" +
                ")");
        // 老数据库补充 title 列（幂等迁移）
        ensureAnnouncementTitleColumn();

        log.info("SQLite: 数据表已就绪");
    }

    /**
     * 为 t_announcement 表补充 title 列（幂等迁移）。
     * 旧版公告表无标题列时自动执行 ALTER TABLE；已存在则跳过。
     */
    private void ensureAnnouncementTitleColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_announcement)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "title".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_announcement ADD COLUMN title TEXT");
            log.info("SQLite: t_announcement 表已补充 title 列");
        }
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
     * 为 t_model_config 补充人民币计费单价列（幂等迁移）。
     * 所有单价统一使用“人民币元/百万 Token”，汇率仅用于展示换算。
     */
    private void ensureModelPricingColumns() {
        Set<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_model_config)").stream()
                .map(c -> String.valueOf(c.get("name")))
                .collect(Collectors.toSet());
        if (!columns.contains("input_price_cny")) {
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN input_price_cny REAL DEFAULT 0");
        }
        if (!columns.contains("output_price_cny")) {
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN output_price_cny REAL DEFAULT 0");
        }
        if (!columns.contains("cached_price_cny")) {
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN cached_price_cny REAL DEFAULT 0");
        }
        if (!columns.contains("reasoning_price_cny")) {
            jdbcTemplate.execute("ALTER TABLE t_model_config ADD COLUMN reasoning_price_cny REAL DEFAULT 0");
        }
    }

    /**
     * 为 t_usage_log 补充人民币成本快照列（幂等迁移）。
     * NULL 表示旧数据，查询时按模型当前价格估算；新数据在 usage 回传后写入快照。
     */
    private void ensureUsageCostColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_usage_log)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "cost_cny".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_usage_log ADD COLUMN cost_cny REAL");
            log.info("SQLite: t_usage_log 表已补充 cost_cny 列");
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
     * 为 t_user 表补充双重验证列（幂等迁移）。
     * 密钥保存 AES-GCM 密文，恢复码仅保存摘要，最近时间步用于阻止验证码重放。
     */
    private void ensureUserTwoFactorColumns() {
        Set<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_user)").stream()
                .map(c -> String.valueOf(c.get("name")))
                .collect(Collectors.toSet());
        if (!columns.contains("two_factor_enabled")) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN two_factor_enabled INTEGER DEFAULT 0");
        }
        if (!columns.contains("two_factor_secret")) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN two_factor_secret TEXT");
        }
        if (!columns.contains("recovery_code_hashes")) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN recovery_code_hashes TEXT DEFAULT '[]'");
        }
        if (!columns.contains("two_factor_last_used_step")) {
            jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN two_factor_last_used_step INTEGER DEFAULT -1");
        }
    }

    /**
     * 为用户表补齐个人资料与头像字段，旧数据库可无损升级。
     */
    private void ensureUserProfileColumns() {
        ensureColumn("t_user", "display_name", "TEXT");
        ensureColumn("t_user", "email", "TEXT");
        ensureColumn("t_user", "phone", "TEXT");
        ensureColumn("t_user", "department", "TEXT");
        ensureColumn("t_user", "job_title", "TEXT");
        ensureColumn("t_user", "bio", "TEXT");
        ensureColumn("t_user", "avatar_type", "TEXT DEFAULT 'default'");
        ensureColumn("t_user", "avatar_value", "TEXT");
    }

    /**
     * 为分享表补齐快照、密码、访问次数与脱敏标记字段。
     */
    private void ensureChatShareEnhancementColumns() {
        ensureColumn("t_chat_share", "snapshot_json", "TEXT");
        ensureColumn("t_chat_share", "password_hash", "TEXT");
        ensureColumn("t_chat_share", "access_count", "INTEGER DEFAULT 0");
        ensureColumn("t_chat_share", "max_views", "INTEGER DEFAULT 0");
        ensureColumn("t_chat_share", "sanitized", "INTEGER DEFAULT 0");
    }

    /**
     * 通用 SQLite 加列迁移：仅在列不存在时执行 ALTER TABLE。
     * @param table 表名（仅内部固定常量调用）
     * @param column 列名（仅内部固定常量调用）
     * @param definition SQLite 列定义
     */
    private void ensureColumn(String table, String column, String definition) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(c -> column.equals(String.valueOf(c.get("name"))));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("SQLite: {} 表已补充 {} 列", table, column);
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

    /**
     * 为 t_chat_user_state 表补充 folders_json 列（幂等迁移）。
     * 该列存储会话文件夹定义列表 JSON（id/名称/折叠态），会话归属关系存于各会话 meta.folderId。
     */
    private void ensureChatUserStateFoldersColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(t_chat_user_state)");
        boolean hasColumn = columns.stream()
                .anyMatch(c -> "folders_json".equals(String.valueOf(c.get("name"))));
        if (!hasColumn) {
            jdbcTemplate.execute("ALTER TABLE t_chat_user_state ADD COLUMN folders_json TEXT");
            log.info("SQLite: t_chat_user_state 表已补充 folders_json 列");
        }
    }

    // ========== t_setting 键值操作（写穿内存缓存） ==========

    /** 启动时全量加载 t_setting 到内存缓存（值为 NULL 的行跳过，读取时同样返回 null） */
    private void loadSettingsCache() {
        settingsCache.clear();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT key, value FROM t_setting")) {
            Object value = row.get("value");
            if (value != null) {
                settingsCache.put(String.valueOf(row.get("key")), String.valueOf(value));
            }
        }
    }

    /**
     * 读取配置值（纯内存缓存读取，所有 t_setting 读写均经过本类，缓存与库始终一致）
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    public String getSetting(String key) {
        return settingsCache.get(key);
    }

    /**
     * 写入配置值（存在则更新，不存在则插入；同步更新内存缓存）
     * @param key 配置键
     * @param value 配置值
     */
    public synchronized void setSetting(String key, String value) {
        int updated = jdbcTemplate.update("UPDATE t_setting SET value = ? WHERE key = ?", value, key);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO t_setting (key, value) VALUES (?, ?)", key, value);
        }
        if (value == null) {
            settingsCache.remove(key);
        } else {
            settingsCache.put(key, value);
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
        u.setDisplayName(rs.getString("display_name"));
        u.setEmail(rs.getString("email"));
        u.setPhone(rs.getString("phone"));
        u.setDepartment(rs.getString("department"));
        u.setJobTitle(rs.getString("job_title"));
        u.setBio(rs.getString("bio"));
        u.setAvatarType(rs.getString("avatar_type"));
        u.setAvatarValue(rs.getString("avatar_value"));
        u.setAllowedModelIds(parseJsonArray(rs.getString("allowed_model_ids")));
        u.setSystemPrompt(rs.getString("system_prompt"));
        u.setPromptPresets(parsePromptPresets(rs.getString("prompt_presets")));
        u.setDisabled(rs.getInt("disabled") == 1);
        u.setDailyLimitType(rs.getString("daily_limit_type"));
        u.setDailyLimitValue(rs.getInt("daily_limit_value"));
        u.setTwoFactorEnabled(rs.getInt("two_factor_enabled") == 1);
        u.setTwoFactorSecret(rs.getString("two_factor_secret"));
        u.setRecoveryCodeHashes(parseJsonArray(rs.getString("recovery_code_hashes")));
        u.setTwoFactorLastUsedStep(rs.getLong("two_factor_last_used_step"));
        return u;
    };

    /**
     * 确保内置 admin 用户存在。
     * 初始密码策略：优先读取 CHATAI_ADMIN_PASSWORD 环境变量 / chatai.admin.password 系统属性，
     * 都未配置时使用兜底弱密码并输出醒目告警（部署文档应提示尽快修改或改用环境变量注入强密码）。
     * 仅对全新安装的库生效，已有 admin 用户的库不会触碰其密码。
     */
    private void ensureAdminUser() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, ADMIN_USERNAME);
        if (count == null || count == 0) {
            String initialPassword = resolveAdminInitialPassword();
            User admin = new User();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(PasswordHasher.hash(initialPassword));
            admin.setRole("admin");
            admin.setCreatedAt(nowString());
            insertUser(admin);
            if (ADMIN_FALLBACK_PASSWORD.equals(initialPassword)) {
                log.warn("SQLite: 已创建内置admin账户并使用系统兜底初始密码。安全提示：请尽快在后台修改密码，" +
                        "或通过环境变量 CHATAI_ADMIN_PASSWORD / 系统属性 chatai.admin.password 指定强密码后重新初始化");
            } else {
                log.info("SQLite: 已创建内置admin账户（使用外部指定的初始密码）");
            }
        }
    }

    /**
     * 解析首次安装 admin 的初始密码：环境变量 CHATAI_ADMIN_PASSWORD > 系统属性 chatai.admin.password > 兜底弱密码
     * @return 初始密码（非空）
     */
    private String resolveAdminInitialPassword() {
        String fromEnv = System.getenv("CHATAI_ADMIN_PASSWORD");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty("chatai.admin.password");
        if (fromProp != null && !fromProp.trim().isEmpty()) {
            return fromProp.trim();
        }
        return ADMIN_FALLBACK_PASSWORD;
    }

    /**
     * 预置模型播种（项目重置/全新部署时的默认数据加载）：
     * 模型表为空且从未播种过时，为每个内置厂商写入首个（旗舰）模型的待启用条目，
     * API Key 留空、默认禁用，管理员补填 Key 后启用即可使用。
     * 播种完成后写入 default_models_seeded 标记，删除播种模型不会重复播种。
     */
    private void seedDefaultModelConfigs() {
        try {
            if ("true".equals(getSetting("default_models_seeded"))) return;
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_model_config", Integer.class);
            if (count != null && count > 0) {
                // 已有模型数据（老库），仅补标记不播种
                setSetting("default_models_seeded", "true");
                return;
            }
            int seeded = 0;
            for (Provider p : providers) {
                if (p.getModels() == null || p.getModels().isEmpty()) continue;
                ProviderModel pm = p.getModels().get(0);
                ModelConfig config = new ModelConfig();
                config.setProviderId(p.getId());
                config.setModelId(pm.getId());
                config.setDisplayName(pm.getName());
                config.setApiKey("");
                config.setApiUrl(p.getDefaultApiUrl());
                config.setProtocol(p.getProtocol());
                config.setSupportsThinking(pm.isSupportsThinking());
                config.setSupportsMultimodal(pm.isSupportsMultimodal());
                config.setEnabled(false);
                config.setVisibleToAll(true);
                config.setBuiltIn(true);
                addModelConfig(config);
                seeded++;
            }
            setSetting("default_models_seeded", "true");
            log.info("SQLite: 已播种 {} 个预置模型（禁用状态，需管理员配置 API Key 后启用）", seeded);
        } catch (Exception e) {
            log.warn("SQLite: 预置模型播种失败（非致命，不影响启动）", e);
        }
    }

    /** 插入用户记录 */
    private void insertUser(User u) {
        jdbcTemplate.update(
                "INSERT INTO t_user (id, username, password, role, created_at, last_login_at, last_login_ip, last_login_browser, display_name, email, phone, department, job_title, bio, avatar_type, avatar_value, allowed_model_ids, system_prompt, disabled, daily_limit_type, daily_limit_value, prompt_presets, two_factor_enabled, two_factor_secret, recovery_code_hashes, two_factor_last_used_step) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                u.getId(), u.getUsername(), u.getPassword(), u.getRole(), u.getCreatedAt(),
                u.getLastLoginAt(), u.getLastLoginIp(), u.getLastLoginBrowser(),
                u.getDisplayName(), u.getEmail(), u.getPhone(), u.getDepartment(), u.getJobTitle(),
                u.getBio(), u.getAvatarType(), u.getAvatarValue(),
                toJsonArray(u.getAllowedModelIds()), u.getSystemPrompt(), u.isDisabled() ? 1 : 0,
                u.getDailyLimitType(), u.getDailyLimitValue(), toPromptPresetsJson(u.getPromptPresets()),
                u.isTwoFactorEnabled() ? 1 : 0, u.getTwoFactorSecret(),
                toJsonArray(u.getRecoveryCodeHashes()), u.getTwoFactorLastUsedStep());
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
            // 密码哈希变更：失效该用户缓存，避免缓存副本保留旧哈希
            userCache.remove(user.getId());
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

    /**
     * 拼接用户查询的 WHERE 子句（用户名模糊匹配），参数追加到 args
     * @return WHERE 子句（含前导空格），无条件返回空字符串
     */
    private String buildUserWhere(String keyword, List<Object> args) {
        if (keyword == null || keyword.isEmpty()) return "";
        args.add("%" + keyword + "%");
        return " WHERE username LIKE ?";
    }

    @Override
    public List<User> queryUsers(String keyword, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildUserWhere(keyword, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM t_user" + where + " ORDER BY created_at ASC LIMIT ? OFFSET ?",
                userRowMapper, args.toArray());
    }

    @Override
    public int countUsers(String keyword) {
        List<Object> args = new ArrayList<>();
        String where = buildUserWhere(keyword, args);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_user" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public User getUserById(String id) {
        if (id == null) return null;
        User cached = userCache.get(id);
        if (cached != null) {
            return copyUser(cached);
        }
        List<User> users = jdbcTemplate.query(
                "SELECT * FROM t_user WHERE id = ?", userRowMapper, id);
        if (users.isEmpty()) return null;
        User user = users.get(0);
        userCache.put(user.getId(), user);
        return copyUser(user);
    }

    /**
     * 用户对象防御性深拷贝：getUserById 对外一律返回副本，
     * 调用方对返回对象的字段修改（如后台编辑页先改内存再保存）不会污染缓存对象
     * @param src 缓存中的用户对象
     * @return 字段一致的副本（列表字段为独立列表实例）
     */
    private User copyUser(User src) {
        User c = new User();
        c.setId(src.getId());
        c.setUsername(src.getUsername());
        c.setPassword(src.getPassword());
        c.setRole(src.getRole());
        c.setCreatedAt(src.getCreatedAt());
        c.setLastLoginAt(src.getLastLoginAt());
        c.setLastLoginIp(src.getLastLoginIp());
        c.setLastLoginBrowser(src.getLastLoginBrowser());
        c.setDisplayName(src.getDisplayName());
        c.setEmail(src.getEmail());
        c.setPhone(src.getPhone());
        c.setDepartment(src.getDepartment());
        c.setJobTitle(src.getJobTitle());
        c.setBio(src.getBio());
        c.setAvatarType(src.getAvatarType());
        c.setAvatarValue(src.getAvatarValue());
        c.setAllowedModelIds(src.getAllowedModelIds() == null
                ? new ArrayList<>() : new ArrayList<>(src.getAllowedModelIds()));
        c.setSystemPrompt(src.getSystemPrompt());
        c.setPromptPresets(src.getPromptPresets() == null
                ? new ArrayList<>() : new ArrayList<>(src.getPromptPresets()));
        c.setDisabled(src.isDisabled());
        c.setDailyLimitType(src.getDailyLimitType());
        c.setDailyLimitValue(src.getDailyLimitValue());
        c.setTwoFactorEnabled(src.isTwoFactorEnabled());
        c.setTwoFactorSecret(src.getTwoFactorSecret());
        c.setRecoveryCodeHashes(src.getRecoveryCodeHashes() == null
                ? new ArrayList<>() : new ArrayList<>(src.getRecoveryCodeHashes()));
        c.setTwoFactorLastUsedStep(src.getTwoFactorLastUsedStep());
        return c;
    }

    @Override
    public boolean deleteUser(String userId) {
        // admin 不可删除
        User user = getUserById(userId);
        if (user == null || "admin".equals(user.getRole())) return false;
        jdbcTemplate.update("DELETE FROM t_token WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_share WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_user_state WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_history WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_file_asset_grant WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_file_asset WHERE owner_user_id = ?", userId);
        int deleted = jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        userCache.remove(userId);
        return deleted > 0;
    }

    @Override
    public void updateUser(User user) {
        jdbcTemplate.update(
                "UPDATE t_user SET username=?, password=?, role=?, created_at=?, last_login_at=?, last_login_ip=?, last_login_browser=?, display_name=?, email=?, phone=?, department=?, job_title=?, bio=?, avatar_type=?, avatar_value=?, allowed_model_ids=?, system_prompt=?, disabled=?, daily_limit_type=?, daily_limit_value=?, prompt_presets=? WHERE id=?",
                user.getUsername(), user.getPassword(), user.getRole(), user.getCreatedAt(),
                user.getLastLoginAt(), user.getLastLoginIp(), user.getLastLoginBrowser(),
                user.getDisplayName(), user.getEmail(), user.getPhone(), user.getDepartment(), user.getJobTitle(),
                user.getBio(), user.getAvatarType(), user.getAvatarValue(),
                toJsonArray(user.getAllowedModelIds()), user.getSystemPrompt(), user.isDisabled() ? 1 : 0,
                user.getDailyLimitType(), user.getDailyLimitValue(), toPromptPresetsJson(user.getPromptPresets()), user.getId());
        userCache.remove(user.getId());
    }

    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) return 1;
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) return 2;
        jdbcTemplate.update("UPDATE t_user SET password = ? WHERE id = ?",
                PasswordHasher.hash(newPassword), userId);
        userCache.remove(userId);
        return 0;
    }

    /**
     * 启用双重验证并保存密钥密文与恢复码摘要。
     */
    @Override
    public boolean enableTwoFactor(String userId, String encryptedSecret, List<String> recoveryCodeHashes) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_enabled=1, two_factor_secret=?, recovery_code_hashes=?, two_factor_last_used_step=-1 WHERE id=? AND two_factor_enabled=0",
                encryptedSecret, toJsonArray(recoveryCodeHashes), userId);
        userCache.remove(userId);
        return updated > 0;
    }

    /**
     * 关闭双重验证并清除密钥、恢复码和验证码重放状态。
     */
    @Override
    public boolean disableTwoFactor(String userId) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_enabled=0, two_factor_secret=NULL, recovery_code_hashes='[]', two_factor_last_used_step=-1 WHERE id=?",
                userId);
        userCache.remove(userId);
        return updated > 0;
    }

    /**
     * 仅在新时间步大于已记录时间步时原子更新，拒绝验证码重放。
     */
    @Override
    public boolean claimTwoFactorStep(String userId, long step) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET two_factor_last_used_step=? WHERE id=? AND two_factor_enabled=1 AND two_factor_last_used_step < ?",
                step, userId, step);
        if (updated > 0) {
            userCache.remove(userId);
        }
        return updated > 0;
    }

    /**
     * 在进程内串行读取并删除恢复码摘要，确保同一码只能成功一次。
     */
    @Override
    public synchronized boolean consumeRecoveryCode(String userId, String recoveryCodeHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT recovery_code_hashes FROM t_user WHERE id=? AND two_factor_enabled=1", userId);
        if (rows.isEmpty()) {
            return false;
        }
        List<String> hashes = parseJsonArray((String) rows.get(0).get("recovery_code_hashes"));
        if (!hashes.remove(recoveryCodeHash)) {
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET recovery_code_hashes=? WHERE id=? AND two_factor_enabled=1",
                toJsonArray(hashes), userId);
        if (updated > 0) {
            userCache.remove(userId);
        }
        return updated > 0;
    }

    /**
     * 用新摘要列表整体替换恢复码，旧恢复码立即全部失效。
     */
    @Override
    public boolean replaceRecoveryCodes(String userId, List<String> recoveryCodeHashes) {
        int updated = jdbcTemplate.update(
                "UPDATE t_user SET recovery_code_hashes=? WHERE id=? AND two_factor_enabled=1",
                toJsonArray(recoveryCodeHashes), userId);
        if (updated > 0) {
            userCache.remove(userId);
        }
        return updated > 0;
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
        // health_check_enabled: NULL 视为 true（默认参与定时健康检查）
        int hce = rs.getInt("health_check_enabled");
        m.setHealthCheckEnabled(rs.wasNull() ? true : hce == 1);
        m.setBuiltIn(rs.getInt("built_in") == 1);
        m.setCreatedAt(rs.getString("created_at"));
        // 连通测试指标（可空，null=未测试）
        int latency = rs.getInt("test_latency_ms");
        m.setTestLatencyMs(rs.wasNull() ? null : latency);
        double speed = rs.getDouble("test_speed");
        m.setTestSpeed(rs.wasNull() ? null : speed);
        m.setTestedAt(rs.getString("tested_at"));
        m.setInputPriceCny(rs.getDouble("input_price_cny"));
        m.setOutputPriceCny(rs.getDouble("output_price_cny"));
        m.setCachedPriceCny(rs.getDouble("cached_price_cny"));
        m.setReasoningPriceCny(rs.getDouble("reasoning_price_cny"));
        return m;
    };

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        // 失效式缓存：写操作后置空，此处按需重建；返回浅拷贝列表，避免调用方增删元素污染缓存
        List<ModelConfig> cached = modelConfigsCache;
        if (cached == null) {
            cached = jdbcTemplate.query("SELECT * FROM t_model_config", modelConfigRowMapper);
            modelConfigsCache = cached;
        }
        return new ArrayList<>(cached);
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
        // 走模型配置列表缓存（失效式缓存由写操作维护），避免每次聊天请求单独查库；
        // 缓存未命中时 getAllModelConfigs 会重建并回填，与直查 DB 结果一致
        for (ModelConfig m : getAllModelConfigs()) {
            if (id != null && id.equals(m.getId())) {
                return m;
            }
        }
        return null;
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
        modelConfigsCache = null;
        return config;
    }

    /** 插入模型配置记录 */
    private void insertModelConfig(ModelConfig m) {
        jdbcTemplate.update(
                "INSERT INTO t_model_config (id, provider_id, provider_name, provider_icon, model_id, display_name, api_key, api_url, protocol, thinking_param_type, supports_thinking, supports_multimodal, enabled, visible_to_all, health_check_enabled, built_in, created_at, test_latency_ms, test_speed, tested_at, input_price_cny, output_price_cny, cached_price_cny, reasoning_price_cny) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                m.getId(), m.getProviderId(), m.getProviderName(), m.getProviderIcon(),
                m.getModelId(), m.getDisplayName(), ApiKeyCrypto.encrypt(m.getApiKey()), m.getApiUrl(),
                m.getProtocol(), m.getThinkingParamType(),
                m.isSupportsThinking() ? 1 : 0, m.isSupportsMultimodal() ? 1 : 0,
                m.isEnabled() ? 1 : 0,
                m.getVisibleToAll() == null ? 1 : (m.getVisibleToAll() ? 1 : 0),
                m.getHealthCheckEnabled() == null ? 1 : (m.getHealthCheckEnabled() ? 1 : 0),
                m.isBuiltIn() ? 1 : 0, m.getCreatedAt(),
                m.getTestLatencyMs(), m.getTestSpeed(), m.getTestedAt(),
                nonNegative(m.getInputPriceCny()), nonNegative(m.getOutputPriceCny()),
                nonNegative(m.getCachedPriceCny()), nonNegative(m.getReasoningPriceCny()));
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        fillProviderInfo(config);
        jdbcTemplate.update(
                "UPDATE t_model_config SET provider_id=?, provider_name=?, provider_icon=?, model_id=?, display_name=?, api_key=?, api_url=?, protocol=?, thinking_param_type=?, supports_thinking=?, supports_multimodal=?, enabled=?, visible_to_all=?, health_check_enabled=?, built_in=?, created_at=?, test_latency_ms=?, test_speed=?, tested_at=?, input_price_cny=?, output_price_cny=?, cached_price_cny=?, reasoning_price_cny=? WHERE id=?",
                config.getProviderId(), config.getProviderName(), config.getProviderIcon(),
                config.getModelId(), config.getDisplayName(), ApiKeyCrypto.encrypt(config.getApiKey()), config.getApiUrl(),
                config.getProtocol(), config.getThinkingParamType(),
                config.isSupportsThinking() ? 1 : 0, config.isSupportsMultimodal() ? 1 : 0,
                config.isEnabled() ? 1 : 0,
                config.getVisibleToAll() == null ? 1 : (config.getVisibleToAll() ? 1 : 0),
                config.getHealthCheckEnabled() == null ? 1 : (config.getHealthCheckEnabled() ? 1 : 0),
                config.isBuiltIn() ? 1 : 0, config.getCreatedAt(),
                config.getTestLatencyMs(), config.getTestSpeed(), config.getTestedAt(),
                nonNegative(config.getInputPriceCny()), nonNegative(config.getOutputPriceCny()),
                nonNegative(config.getCachedPriceCny()), nonNegative(config.getReasoningPriceCny()),
                config.getId());
        modelConfigsCache = null;
    }

    @Override
    public boolean deleteModelConfig(String id) {
        int deleted = jdbcTemplate.update("DELETE FROM t_model_config WHERE id = ?", id);
        modelConfigsCache = null;
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
        String providerId = config.getProviderId();
        if (providerId == null || providerId.isBlank() || "custom".equals(providerId)
                || "__custom__".equals(providerId)) {
            providerId = ensureCustomProvider(config);
            config.setProviderId(providerId);
        }
        Provider resolved = getResolvedProvider(providerId);
        if (resolved == null) return;
        if (config.getProviderName() == null || config.getProviderName().isBlank()) {
            config.setProviderName(resolved.getName());
        }
        if (config.getProviderIcon() == null || config.getProviderIcon().isBlank()) {
            config.setProviderIcon(resolved.getIcon());
        }
        if (config.getApiUrl() == null || config.getApiUrl().isBlank()) {
            config.setApiUrl(resolved.getDefaultApiUrl());
        }
        if (config.getProtocol() == null || config.getProtocol().isBlank()) {
            config.setProtocol(resolved.getProtocol());
        }
        if (config.getThinkingParamType() == null || config.getThinkingParamType().isBlank()) {
            config.setThinkingParamType(resolved.getThinkingParamType());
        }
        if (providerId.startsWith("custom-")) {
            appendCustomProviderModel(providerId, config);
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

    /**
     * 将旧版所有 provider_id=custom/__custom__ 的模型按厂商名称分组，创建唯一厂商记录并回写模型。
     */
    private synchronized void migrateLegacyCustomProviders() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT provider_name, provider_icon, api_url, protocol, thinking_param_type " +
                        "FROM t_model_config WHERE provider_id IN ('custom','__custom__') ORDER BY created_at");
        Set<String> migratedNames = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = cleanText(row.get("provider_name"));
            if (name.isEmpty() || !migratedNames.add(name)) continue;
            String id = findCustomProviderIdByName(name);
            if (id == null) {
                id = createCustomProvider(name, cleanText(row.get("provider_icon")),
                        cleanText(row.get("api_url")), cleanText(row.get("protocol")),
                        cleanText(row.get("thinking_param_type")));
            }
            jdbcTemplate.update("UPDATE t_model_config SET provider_id=?, provider_icon=COALESCE(NULLIF(provider_icon,''), ?) " +
                    "WHERE provider_id IN ('custom','__custom__') AND provider_name=?", id,
                    cleanText(row.get("provider_icon")), name);
        }
        if (!migratedNames.isEmpty()) {
            modelConfigsCache = null;
            for (String name : migratedNames) {
                String id = findCustomProviderIdByName(name);
                if (id != null) rebuildCustomProviderModels(id);
            }
            log.info("SQLite: 已迁移 {} 个历史自定义厂商为唯一 ID", migratedNames.size());
        }
    }

    /**
     * 为新自定义模型查找或创建厂商，返回唯一厂商 ID。
     */
    private synchronized String ensureCustomProvider(ModelConfig config) {
        String name = config.getProviderName() == null ? "自定义厂商" : config.getProviderName().trim();
        if (name.isEmpty()) name = "自定义厂商";
        String existingId = findCustomProviderIdByName(name);
        if (existingId != null) return existingId;
        return createCustomProvider(name, config.getProviderIcon(), config.getApiUrl(),
                config.getProtocol(), config.getThinkingParamType());
    }

    /**
     * 新建自定义厂商并生成不可冲突的稳定 ID。
     */
    private String createCustomProvider(String name, String icon, String apiUrl,
                                        String protocol, String thinkingParamType) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "provider";
        String id;
        do {
            id = "custom-" + base + "-" + UUID.randomUUID().toString().substring(0, 8);
        } while (getCustomProviderRow(id) != null);
        String providerIcon = cleanText(icon);
        if (providerIcon.isEmpty()) {
            providerIcon = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        }
        String now = nowString();
        jdbcTemplate.update("INSERT INTO t_custom_provider " +
                        "(id,name,icon,api_url,protocol,thinking_param_type,models_json,created_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)",
                id, name, providerIcon, cleanText(apiUrl),
                cleanText(protocol).isEmpty() ? "openai" : cleanText(protocol),
                cleanText(thinkingParamType).isEmpty() ? "default" : cleanText(thinkingParamType),
                "[]", now, now);
        return id;
    }

    /**
     * 按显示名查询自定义厂商 ID，供旧数据迁移与“选择已有厂商”自动复用。
     */
    private String findCustomProviderIdByName(String name) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM t_custom_provider WHERE name=? LIMIT 1", String.class, name);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 查询单个自定义厂商原始行。
     */
    private Map<String, Object> getCustomProviderRow(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM t_custom_provider WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把任意数据库值安全规整为去首尾空白字符串。
     */
    private String cleanText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 将一个已接入模型补入自定义厂商支持模型列表，避免图标/模型目录与模型配置脱节。
     */
    private synchronized void appendCustomProviderModel(String providerId, ModelConfig config) {
        Provider provider = getResolvedProvider(providerId);
        if (provider == null) return;
        List<ProviderModel> models = provider.getModels() == null
                ? new ArrayList<>() : new ArrayList<>(provider.getModels());
        boolean exists = models.stream().anyMatch(m -> Objects.equals(m.getId(), config.getModelId()));
        if (!exists && config.getModelId() != null && !config.getModelId().isBlank()) {
            ProviderModel model = new ProviderModel();
            model.setId(config.getModelId());
            model.setName(config.getDisplayName() == null || config.getDisplayName().isBlank()
                    ? config.getModelId() : config.getDisplayName());
            model.setSupportsThinking(config.isSupportsThinking());
            model.setSupportsMultimodal(config.isSupportsMultimodal());
            models.add(model);
            saveProviderModels(providerId, models);
        }
    }

    /**
     * 根据已接入模型重建自定义厂商模型目录，供旧数据迁移使用。
     */
    private void rebuildCustomProviderModels(String providerId) {
        List<ProviderModel> models = new ArrayList<>();
        for (ModelConfig config : getAllModelConfigs()) {
            if (!providerId.equals(config.getProviderId())) continue;
            ProviderModel model = new ProviderModel();
            model.setId(config.getModelId());
            model.setName(config.getDisplayName());
            model.setSupportsThinking(config.isSupportsThinking());
            model.setSupportsMultimodal(config.isSupportsMultimodal());
            models.add(model);
        }
        saveProviderModels(providerId, models);
    }

    @Override
    public List<Provider> getAllProviders() {
        Map<String, List<ProviderModel>> modelOverrides = loadProviderModelOverrides();
        List<Provider> copy = new ArrayList<>();
        for (Provider p : providers) {
            Provider c = new Provider();
            c.setId(p.getId());
            c.setName(getProviderDisplayName(p.getId()));
            c.setIcon(p.getIcon());
            c.setDefaultApiUrl(p.getDefaultApiUrl());
            c.setProtocol(p.getProtocol());
            c.setThinkingParamType(p.getThinkingParamType());
            c.setModels(modelOverrides.getOrDefault(p.getId(), p.getModels()));
            copy.add(c);
        }
        return copy;
    }

    /**
     * 查询预置或自定义厂商的完整可用配置。
     * @param providerId 厂商唯一 ID
     * @return 厂商配置，不存在返回 null
     */
    public Provider getResolvedProvider(String providerId) {
        if (providerId == null) return null;
        for (Provider provider : getAllProviders()) {
            if (providerId.equals(provider.getId())) return provider;
        }
        Map<String, Object> row = getCustomProviderRow(providerId);
        if (row == null) return null;
        Provider custom = new Provider();
        custom.setId(cleanText(row.get("id")));
        custom.setName(cleanText(row.get("name")));
        custom.setIcon(cleanText(row.get("icon")));
        custom.setDefaultApiUrl(cleanText(row.get("api_url")));
        custom.setProtocol(cleanText(row.get("protocol")));
        custom.setThinkingParamType(cleanText(row.get("thinking_param_type")));
        custom.setModels(parseProviderModels(cleanText(row.get("models_json"))));
        return custom;
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
        if (providerId.startsWith("custom-") || providerId.startsWith("__custom__")) {
            Map<String, Object> row = getCustomProviderRow(providerId);
            if (row == null) throw new IllegalArgumentException("自定义厂商不存在: " + providerId);
            if (!trimmed.equals(cleanText(row.get("name"))) && findCustomProviderIdByName(trimmed) != null) {
                throw new IllegalArgumentException("已存在同名自定义厂商");
            }
            String icon = trimmedIcon.isEmpty() ? cleanText(row.get("icon")) : trimmedIcon;
            jdbcTemplate.update("UPDATE t_custom_provider SET name=?, icon=?, updated_at=? WHERE id=?",
                    trimmed, icon, nowString(), providerId);
            int updated = jdbcTemplate.update(
                    "UPDATE t_model_config SET provider_name=?, provider_icon=? WHERE provider_id=?",
                    trimmed, icon, providerId);
            modelConfigsCache = null;
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
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT * FROM t_custom_provider ORDER BY created_at, name")) {
            String id = cleanText(row.get("id"));
            Provider provider = getResolvedProvider(id);
            List<ProviderModel> models = provider == null || provider.getModels() == null
                    ? Collections.emptyList() : provider.getModels();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", cleanText(row.get("name")));
            m.put("modelCount", models.size());
            m.put("icon", cleanText(row.get("icon")));
            m.put("defaultApiUrl", cleanText(row.get("api_url")));
            m.put("protocol", cleanText(row.get("protocol")));
            m.put("thinkingParamType", cleanText(row.get("thinking_param_type")));
            m.put("models", models);
            m.put("type", "custom");
            result.add(m);
        }
        return result;
    }

    /**
     * 保存厂商支持的模型目录；预置厂商写覆盖设置，自定义厂商写独立表。
     * @param providerId 厂商唯一 ID
     * @param models 上游返回并经管理员确认的模型列表
     */
    public synchronized void saveProviderModels(String providerId, List<ProviderModel> models) {
        List<ProviderModel> safeModels = models == null ? new ArrayList<>() : models.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.getId() != null && !m.getId().isBlank())
                .collect(Collectors.toMap(ProviderModel::getId, m -> m, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
        try {
            String json = objectMapper.writeValueAsString(safeModels);
            if (providerId != null && providerId.startsWith("custom-")) {
                if (jdbcTemplate.update("UPDATE t_custom_provider SET models_json=?, updated_at=? WHERE id=?",
                        json, nowString(), providerId) == 0) {
                    throw new IllegalArgumentException("自定义厂商不存在: " + providerId);
                }
                return;
            }
            Map<String, List<ProviderModel>> overrides = loadProviderModelOverrides();
            overrides.put(providerId, safeModels);
            setSetting("provider_model_overrides", objectMapper.writeValueAsString(overrides));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存厂商模型目录失败", e);
        }
    }

    /**
     * 读取预置厂商的模型目录覆盖设置。
     */
    private Map<String, List<ProviderModel>> loadProviderModelOverrides() {
        String json = getSetting("provider_model_overrides");
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, List<ProviderModel>>>() {});
        } catch (Exception e) {
            log.warn("读取厂商模型目录覆盖失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析自定义厂商模型目录 JSON。
     */
    private List<ProviderModel> parseProviderModels(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<ProviderModel> parsed = objectMapper.readValue(json,
                    new TypeReference<List<ProviderModel>>() {});
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            return new ArrayList<>();
        }
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
        l.setRequestId(rs.getString("request_id"));
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
        double cost = rs.getDouble("cost_cny");
        l.setCostCny(rs.wasNull() ? null : cost);
        return l;
    };

    @Override
    public void addUsageLog(UsageLog logEntry) {
        jdbcTemplate.update(
                "INSERT INTO t_usage_log (request_id, user_id, username, model_id, model_name, timestamp, prompt_tokens, completion_tokens, cached_tokens, reasoning_tokens, deep_thinking, cost_cny) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                logEntry.getRequestId(), logEntry.getUserId(), logEntry.getUsername(), logEntry.getModelId(),
                logEntry.getModelName(), logEntry.getTimestamp(),
                logEntry.getPromptTokens(), logEntry.getCompletionTokens(),
                logEntry.getCachedTokens(), logEntry.getReasoningTokens(),
                logEntry.isDeepThinking() ? 1 : 0, logEntry.getCostCny());
    }

    /**
     * 获取使用记录（带安全上限的全量兜底查询）。
     * 主查询路径均为分页接口（queryUsageLogs/aggregateUsageStats），
     * 本方法仅作兼容保留，限制最多返回最近 {@value #USAGE_LOG_FETCH_LIMIT} 条，
     * 防止数据量增长后一次性装载全部记录导致内存溢出。
     * @return 最近的使用记录列表（时间降序）
     */
    @Override
    public List<UsageLog> getAllUsageLogs() {
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log ORDER BY timestamp DESC LIMIT " + USAGE_LOG_FETCH_LIMIT,
                usageLogRowMapper);
    }

    /**
     * 获取指定用户的使用记录（带安全上限，最近 {@value #USAGE_LOG_FETCH_LIMIT} 条）
     * @param userId 用户ID
     * @return 该用户最近的使用记录列表（时间降序）
     */
    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log WHERE user_id = ? ORDER BY timestamp DESC LIMIT " + USAGE_LOG_FETCH_LIMIT,
                usageLogRowMapper, userId);
    }

    @Override
    public int countUsageByUserAndDay(String userId, String day) {
        // 范围比较替代 LIKE 前缀，可命中 idx_usage_user_time 复合索引
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                Integer.class, userId, day, nextDay(day));
        return count == null ? 0 : count;
    }

    @Override
    public long sumTokensByUserAndDay(String userId, String day) {
        // 范围比较替代 LIKE 前缀，可命中 idx_usage_user_time 复合索引
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                Long.class, userId, day, nextDay(day));
        return sum == null ? 0L : sum;
    }

    @Override
    public double sumCostCnyByUserAndDay(String userId, String day) {
        List<UsageLog> rows = jdbcTemplate.query(
                "SELECT * FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                usageLogRowMapper, userId, day, nextDay(day));
        double total = 0D;
        for (UsageLog row : rows) total += row.getCostCny() != null ? row.getCostCny() : calculateCostCny(row);
        return total;
    }

    /**
     * 计算指定日期的下一天字符串（yyyy-MM-dd），供 timestamp 范围比较的上界使用
     * （timestamp 为 yyyy-MM-dd HH:mm:ss 文本，字典序比较即时间比较）
     * @param day 日期字符串
     * @return 下一天日期字符串，解析失败返回 day 原值（退化为单日 LIKE 语义也不抛错）
     */
    private String nextDay(String day) {
        try {
            return LocalDate.parse(day).plusDays(1).toString();
        } catch (Exception e) {
            return day;
        }
    }

    @Override
    public void updateUsageLog(UsageLog updatedLog) {
        double cost = calculateCostCny(updatedLog);
        if (updatedLog.getRequestId() != null && !updatedLog.getRequestId().isBlank()) {
            jdbcTemplate.update(
                    "UPDATE t_usage_log SET prompt_tokens=?, completion_tokens=?, cached_tokens=?, reasoning_tokens=?, deep_thinking=?, cost_cny=? WHERE request_id=?",
                    updatedLog.getPromptTokens(), updatedLog.getCompletionTokens(),
                    updatedLog.getCachedTokens(), updatedLog.getReasoningTokens(),
                    updatedLog.isDeepThinking() ? 1 : 0, cost, updatedLog.getRequestId());
        } else {
            // 兼容升级前已构造、没有 requestId 的调用。
            jdbcTemplate.update(
                    "UPDATE t_usage_log SET prompt_tokens=?, completion_tokens=?, cached_tokens=?, reasoning_tokens=?, deep_thinking=?, cost_cny=? WHERE user_id=? AND timestamp=? AND model_id=?",
                    updatedLog.getPromptTokens(), updatedLog.getCompletionTokens(),
                    updatedLog.getCachedTokens(), updatedLog.getReasoningTokens(),
                    updatedLog.isDeepThinking() ? 1 : 0, cost,
                    updatedLog.getUserId(), updatedLog.getTimestamp(), updatedLog.getModelId());
        }
        updatedLog.setCostCny(calculateCostCny(updatedLog));
    }

    /**
     * 按模型当前人民币单价计算使用成本。缓存和推理 Token 从普通输入/输出中扣除，避免重复计费。
     * @param usage 使用记录
     * @return 人民币元成本，保留至少 8 位计算精度
     */
    public double calculateCostCny(UsageLog usage) {
        ModelConfig model = getModelConfigById(usage.getModelId());
        if (model == null) return 0D;
        long cached = Math.max(0, Math.min(usage.getCachedTokens(), usage.getPromptTokens()));
        long reasoning = Math.max(0, Math.min(usage.getReasoningTokens(), usage.getCompletionTokens()));
        long regularInput = Math.max(0, usage.getPromptTokens() - cached);
        long regularOutput = Math.max(0, usage.getCompletionTokens() - reasoning);
        double reasoningPrice = model.getReasoningPriceCny() > 0
                ? model.getReasoningPriceCny() : model.getOutputPriceCny();
        return (regularInput * nonNegative(model.getInputPriceCny())
                + cached * nonNegative(model.getCachedPriceCny())
                + regularOutput * nonNegative(model.getOutputPriceCny())
                + reasoning * nonNegative(reasoningPrice)) / 1_000_000D;
    }

    /** 将非法或负数价格归零，防止管理端错误输入污染计费。 */
    private double nonNegative(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0D;
    }

    @Override
    public List<String> getUsageLogDates() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT SUBSTR(timestamp, 1, 10) AS day FROM t_usage_log ORDER BY day",
                String.class);
    }

    /**
     * 删除指定日期之前的使用记录（定期清理过期日志用）。
     * 范围比较替代 SUBSTR 列函数包裹，可命中 idx_usage_time 索引。
     * @param day 截止日期（yyyy-MM-dd，不含当天）
     * @return 删除条数
     */
    public int deleteUsageLogsBefore(String day) {
        return jdbcTemplate.update(
                "DELETE FROM t_usage_log WHERE timestamp < ?", day);
    }

    // ========== 使用记录查询下推（筛选/分页/聚合在 SQL 层完成） ==========

    /**
     * 拼接用量查询的 WHERE 子句（username/modelName/日期范围），参数追加到 args
     * @return WHERE 子句（含前导空格），无条件返回空字符串
     */
    private String buildUsageWhere(String username, String modelName, String startDate, String endDate,
                                   List<Object> args) {
        List<String> conds = new ArrayList<>();
        if (username != null && !username.isEmpty()) {
            conds.add("username = ?");
            args.add(username);
        }
        if (modelName != null && !modelName.isEmpty()) {
            conds.add("model_name = ?");
            args.add(modelName);
        }
        // timestamp 为 yyyy-MM-dd HH:mm:ss，字典序比较即日期比较（含边界当天），可命中 idx_usage_time
        if (startDate != null && !startDate.isEmpty()) {
            conds.add("timestamp >= ?");
            args.add(startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            conds.add("timestamp < ?");
            args.add(LocalDate.parse(endDate).plusDays(1).toString());
        }
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    @Override
    public List<UsageLog> queryUsageLogs(String username, String modelName, String startDate, String endDate,
                                         int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildUsageWhere(username, modelName, startDate, endDate, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log" + where + " ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                usageLogRowMapper, args.toArray());
    }

    @Override
    public int countUsageLogs(String username, String modelName, String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildUsageWhere(username, modelName, startDate, endDate, args);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public Map<String, Long> summarizeUsage(String username, String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildUsageWhere(username, null, startDate, endDate, args);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(prompt_tokens),0) AS prompt_tokens, " +
                "COALESCE(SUM(completion_tokens),0) AS completion_tokens, " +
                "COALESCE(SUM(reasoning_tokens),0) AS reasoning_tokens FROM t_usage_log" + where,
                args.toArray());
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("calls", ((Number) row.get("calls")).longValue());
        result.put("promptTokens", ((Number) row.get("prompt_tokens")).longValue());
        result.put("completionTokens", ((Number) row.get("completion_tokens")).longValue());
        result.put("reasoningTokens", ((Number) row.get("reasoning_tokens")).longValue());
        return result;
    }

    /**
     * 拼接聚合统计的 WHERE 子句（modelName/日期范围 + username IN），参数追加到 args
     * @return WHERE 子句（含前导空格），无条件返回空字符串
     */
    private String buildStatsWhere(List<String> usernames, String modelName,
                                   String startDate, String endDate, List<Object> args) {
        String where = buildUsageWhere(null, modelName, startDate, endDate, args);
        if (usernames != null && !usernames.isEmpty()) {
            String in = usernames.stream().map(u -> "?").collect(Collectors.joining(","));
            where += (where.isEmpty() ? " WHERE " : " AND ") + "username IN (" + in + ")";
            args.addAll(usernames);
        }
        return where;
    }

    /** 聚合统计的 SELECT + GROUP BY 主体（复用于全量与分页查询） */
    private static final String USAGE_STATS_SELECT =
            "SELECT COALESCE(username,'未知') AS username, " +
            "COALESCE(SUBSTR(timestamp,1,10),'未知') AS date, " +
            "COALESCE(model_name,'未知') AS model_name, " +
            "COALESCE(model_id,'') AS model_id, " +
            "COUNT(*) AS count, " +
            "COALESCE(SUM(prompt_tokens),0) AS prompt_tokens, " +
            "COALESCE(SUM(completion_tokens),0) AS completion_tokens, " +
            "COALESCE(SUM(cached_tokens),0) AS cached_tokens, " +
            "COALESCE(SUM(reasoning_tokens),0) AS reasoning_tokens, " +
            "COALESCE(SUM(cost_cny),0) AS cost_cny, " +
            "COUNT(cost_cny) AS cost_snapshots, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN prompt_tokens ELSE 0 END),0) AS unpriced_prompt_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN completion_tokens ELSE 0 END),0) AS unpriced_completion_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN cached_tokens ELSE 0 END),0) AS unpriced_cached_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN reasoning_tokens ELSE 0 END),0) AS unpriced_reasoning_tokens, " +
            "COALESCE(SUM(deep_thinking),0) AS thinking_count " +
            "FROM t_usage_log";

    private static final String USAGE_STATS_GROUP_ORDER =
            " GROUP BY 1, 2, 3, 4 ORDER BY date DESC, username ASC, model_name ASC";

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName,
                                                         String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildStatsWhere(usernames, modelName, startDate, endDate, args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                USAGE_STATS_SELECT + where + USAGE_STATS_GROUP_ORDER, args.toArray());
        return mapUsageStatRows(rows);
    }

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName,
                                                         String startDate, String endDate,
                                                         int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildStatsWhere(usernames, modelName, startDate, endDate, args);
        args.add(limit);
        args.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                USAGE_STATS_SELECT + where + USAGE_STATS_GROUP_ORDER + " LIMIT ? OFFSET ?",
                args.toArray());
        return mapUsageStatRows(rows);
    }

    @Override
    public int countUsageStatGroups(List<String> usernames, String modelName,
                                    String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildStatsWhere(usernames, modelName, startDate, endDate, args);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM t_usage_log" + where + " GROUP BY " +
                "COALESCE(username,'未知'), COALESCE(SUBSTR(timestamp,1,10),'未知'), COALESCE(model_name,'未知'), COALESCE(model_id,''))",
                Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 聚合统计行 → 驼峰字段 Map 列表 */
    private List<Map<String, Object>> mapUsageStatRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> statsList = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("username", row.get("username"));
            item.put("date", row.get("date"));
            item.put("modelName", row.get("model_name"));
            item.put("modelId", row.get("model_id"));
            item.put("count", ((Number) row.get("count")).intValue());
            item.put("promptTokens", ((Number) row.get("prompt_tokens")).intValue());
            item.put("completionTokens", ((Number) row.get("completion_tokens")).intValue());
            item.put("cachedTokens", ((Number) row.get("cached_tokens")).intValue());
            item.put("reasoningTokens", ((Number) row.get("reasoning_tokens")).intValue());
            item.put("costCny", ((Number) row.get("cost_cny")).doubleValue());
            item.put("costSnapshots", ((Number) row.get("cost_snapshots")).longValue());
            item.put("unpricedPromptTokens", ((Number) row.get("unpriced_prompt_tokens")).intValue());
            item.put("unpricedCompletionTokens", ((Number) row.get("unpriced_completion_tokens")).intValue());
            item.put("unpricedCachedTokens", ((Number) row.get("unpriced_cached_tokens")).intValue());
            item.put("unpricedReasoningTokens", ((Number) row.get("unpriced_reasoning_tokens")).intValue());
            item.put("thinkingCount", ((Number) row.get("thinking_count")).longValue());
            statsList.add(item);
        }
        return statsList;
    }


    @Override
    public List<String> getUsageUsernames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT username FROM t_usage_log WHERE username IS NOT NULL ORDER BY username",
                String.class);
    }

    @Override
    public List<String> getUsageModelNames(String username) {
        if (username != null && !username.isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT model_name FROM t_usage_log WHERE model_name IS NOT NULL AND username = ? ORDER BY model_name",
                    String.class, username);
        }
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT model_name FROM t_usage_log WHERE model_name IS NOT NULL ORDER BY model_name",
                String.class);
    }

    // ========== 聊天记录（按会话行存储 + 旧整文档兼容） ==========

    /**
     * 加载用户聊天记录 JSON 文档（旧整文档表，仅作按会话行迁移来源与备份）
     * @param userId 用户ID
     * @return JSON 字符串，不存在返回 null
     */
    public String loadChatData(String userId) {
        List<String> list = jdbcTemplate.queryForList(
                "SELECT chat_data FROM t_chat_history WHERE user_id = ?", String.class, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 删除用户聊天记录（整文档备份 + 按会话行 + 用户状态三张表一并清理）
     * @param userId 用户ID
     */
    public void deleteChatData(String userId) {
        jdbcTemplate.update("DELETE FROM t_chat_history WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_chat_user_state WHERE user_id = ?", userId);
    }

    /**
     * 判断用户是否已存在会话状态行（存在即表示已完成按会话行迁移）
     * @param userId 用户ID
     * @return true=已迁移
     */
    public boolean hasChatUserState(String userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_chat_user_state WHERE user_id = ?", Integer.class, userId);
        return n != null && n > 0;
    }

    /**
     * 加载用户会话全局状态
     * @param userId 用户ID
     * @return 含 last_chat_id、deleted_chat_ids 的记录，不存在返回 null
     */
    public Map<String, Object> loadChatUserState(String userId) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT last_chat_id, deleted_chat_ids, folders_json FROM t_chat_user_state WHERE user_id = ?", userId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 保存用户会话全局状态（存在则更新，不存在则插入）
     * @param userId 用户ID
     * @param lastChatId 最后所在会话ID
     * @param deletedChatIdsJson 已删除会话ID列表 JSON
     * @param foldersJson 会话文件夹定义列表 JSON（null 表示清空文件夹）
     * @param updatedAt 更新时间字符串
     * @param updatedAtTs 更新时间戳
     */
    public void saveChatUserState(String userId, String lastChatId, String deletedChatIdsJson,
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

    /**
     * 查询用户会话数据的版本号（会话行与全局状态行 updated_at_ts 的最大值），
     * 供多端轻量变更检测：版本未变化时前端无需重拉摘要列表
     * @param userId 用户ID
     * @return 版本号（毫秒时间戳），无任何数据时返回 0
     */
    public long getChatHistoryVersion(String userId) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT MAX(ts) FROM (" +
                "SELECT MAX(updated_at_ts) AS ts FROM t_chat_session WHERE user_id = ? " +
                "UNION ALL " +
                "SELECT MAX(updated_at_ts) AS ts FROM t_chat_user_state WHERE user_id = ?)",
                Long.class, userId, userId);
        return v != null ? v : 0L;
    }

    /**
     * 加载用户全部会话行（含消息正文，导出/全文搜索等全量场景用）
     * @param userId 用户ID
     * @return 每条记录含 chat_id/messages/meta，按插入顺序返回
     */
    public List<Map<String, Object>> listChatSessions(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, messages, meta FROM t_chat_session WHERE user_id = ? ORDER BY rowid", userId);
    }

    /**
     * 加载用户全部会话摘要行（仅冗余摘要列 + 元信息，不含消息正文，侧边栏首屏用）
     * @param userId 用户ID
     * @return 每条记录含 chat_id/title/preview/last_time/msg_count/meta，按插入顺序返回
     */
    public List<Map<String, Object>> listChatSessionSummaries(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, title, preview, last_time, msg_count, meta FROM t_chat_session WHERE user_id = ? ORDER BY rowid",
                userId);
    }

    /**
     * 加载单个会话行（切换会话按需加载用）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 含 messages/meta 的记录，不存在返回 null
     */
    public Map<String, Object> getChatSession(String userId, String chatId) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT messages, meta FROM t_chat_session WHERE user_id = ? AND chat_id = ?", userId, chatId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询用户全部会话 ID（仅 ID 列，分享状态判定等存在性检查用，不加载消息正文）
     * @param userId 用户ID
     * @return 会话ID列表
     */
    public List<String> listChatSessionIds(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id FROM t_chat_session WHERE user_id = ?", String.class, userId);
    }

    /**
     * 按关键字粗筛标题或消息正文命中的会话行（SQL LIKE 下推，跨会话全文搜索用）。
     * 仅做候选集收窄：消息 LIKE 命中的是 JSON 全文（可能误命中字段名等非内容文本），
     * 调用方需对标题和消息内容做精确二次匹配。按最近更新时间倒序，限定候选会话数防止重度用户全表解析。
     * @param userId 用户ID
     * @param keyword 关键字（不含 %/_ 通配符语义，原样作为子串匹配）
     * @param sessionLimit 候选会话数上限
     * @return 每条记录含 chat_id/title/messages，按 updated_at_ts 降序
     */
    public List<Map<String, Object>> searchChatSessionsByKeyword(String userId, String keyword, int sessionLimit) {
        return jdbcTemplate.queryForList(
                "SELECT chat_id, title, messages FROM t_chat_session " +
                "WHERE user_id = ? AND (title LIKE ? OR messages LIKE ?) ORDER BY updated_at_ts DESC LIMIT ?",
                userId, "%" + keyword + "%", "%" + keyword + "%", sessionLimit);
    }

    /**
     * 插入或更新单个会话行
     * @param userId 用户ID
     * @param chatId 会话ID
     * @param messagesJson 消息列表 JSON
     * @param metaJson 会话元信息 JSON（null 时保留原值，上传数据可能不带该会话的元信息）
     * @param title 会话标题
     * @param preview 首条用户消息预览
     * @param lastTime 最后消息时间
     * @param msgCount 消息条数
     * @param updatedAt 更新时间字符串
     * @param updatedAtTs 更新时间戳
     */
    public void upsertChatSession(String userId, String chatId, String messagesJson, String metaJson,
                                   String title, String preview, String lastTime, int msgCount,
                                   String updatedAt, long updatedAtTs) {
        int updated = jdbcTemplate.update(
                "UPDATE t_chat_session SET messages=?, meta=COALESCE(?, meta), title=?, preview=?, last_time=?, " +
                "msg_count=?, updated_at=?, updated_at_ts=? WHERE user_id=? AND chat_id=?",
                messagesJson, metaJson, title, preview, lastTime, msgCount, updatedAt, updatedAtTs, userId, chatId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO t_chat_session (user_id, chat_id, messages, meta, title, preview, last_time, msg_count, updated_at, updated_at_ts) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    userId, chatId, messagesJson, metaJson, title, preview, lastTime, msgCount, updatedAt, updatedAtTs);
        }
    }

    /**
     * 删除用户的指定会话行（同步 deletedChatIds 时清理）
     * @param userId 用户ID
     * @param chatIds 待删除的会话ID集合
     */
    public void deleteChatSessions(String userId, Collection<String> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) return;
        for (String chatId : chatIds) {
            jdbcTemplate.update("DELETE FROM t_chat_session WHERE user_id = ? AND chat_id = ?", userId, chatId);
        }
    }

    /**
     * 加载全部聊天数据的原始 JSON 文本（含按会话行与旧整文档备份），
     * 供孤儿上传文件清理时正则提取附件引用，不做 JSON 解析避免解析失败遗漏引用
     * @return 原始 JSON 文本列表
     */
    public List<String> listAllChatPayloads() {
        List<String> payloads = new ArrayList<>();
        payloads.addAll(jdbcTemplate.queryForList("SELECT chat_data FROM t_chat_history", String.class));
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT messages, meta FROM t_chat_session")) {
            if (row.get("messages") instanceof String s) payloads.add(s);
            if (row.get("meta") instanceof String s) payloads.add(s);
        }
        return payloads;
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
        s.setSnapshotJson(rs.getString("snapshot_json"));
        s.setPasswordHash(rs.getString("password_hash"));
        s.setAccessCount(rs.getInt("access_count"));
        s.setMaxViews(rs.getInt("max_views"));
        s.setSanitized(rs.getInt("sanitized") == 1);
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
                "INSERT INTO t_chat_share (id, chat_id, user_id, user_name, title, created_at, expires_at, snapshot_json, password_hash, access_count, max_views, sanitized) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                s.getId(), s.getChatId(), s.getUserId(), s.getUserName(), s.getTitle(), s.getCreatedAt(),
                s.getExpiresAt(), s.getSnapshotJson(), s.getPasswordHash(), s.getAccessCount(),
                s.getMaxViews(), s.isSanitized() ? 1 : 0);
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
     * 获取全部用户的分享记录（新建在前，后台分享管理用）
     * @return 分享列表
     */
    public List<ChatShare> getAllChatShares() {
        return jdbcTemplate.query(
                "SELECT * FROM t_chat_share ORDER BY created_at DESC", chatShareRowMapper);
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

    /** 执行最小只读查询，供健康检查确认数据库可用。 */
    public boolean isReady() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 更新复用分享码的快照、有效期、密码与访问上限，并重置访问次数。
     */
    public void updateChatShareDetails(ChatShare share) {
        jdbcTemplate.update("UPDATE t_chat_share SET title=?, expires_at=?, snapshot_json=?, password_hash=?, " +
                        "access_count=0, max_views=?, sanitized=? WHERE id=?",
                share.getTitle(), share.getExpiresAt(), share.getSnapshotJson(), share.getPasswordHash(),
                share.getMaxViews(), share.isSanitized() ? 1 : 0, share.getId());
        share.setAccessCount(0);
    }

    /**
     * 原子占用一次分享访问额度，避免并发访问越过 maxViews。
     * @param id 分享码
     * @return true=允许访问并已计数
     */
    public boolean claimChatShareAccess(String id) {
        return jdbcTemplate.update("UPDATE t_chat_share SET access_count=access_count+1 " +
                "WHERE id=? AND (max_views<=0 OR access_count<max_views)", id) > 0;
    }

    // ========== 上传资源所有权 ==========

    /** 记录新上传资源的所有者。 */
    public void registerFileAsset(String url, String ownerUserId, String assetType) {
        jdbcTemplate.update("INSERT INTO t_file_asset(url,owner_user_id,asset_type,created_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(url) DO UPDATE SET owner_user_id=excluded.owner_user_id, asset_type=excluded.asset_type",
                url, ownerUserId, assetType, nowString());
    }

    /** 为复制分享的用户授予既有资源访问权，不改变原始所有者。 */
    public void grantFileAssetAccess(String url, String userId) {
        String normalizedUrl = normalizeAssetUrl(url);
        if (normalizedUrl.isEmpty() || userId == null || userId.isBlank()) return;
        Integer tracked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_file_asset WHERE url=?", Integer.class, normalizedUrl);
        if (tracked == null || tracked == 0) return;
        jdbcTemplate.update("INSERT OR IGNORE INTO t_file_asset_grant(url,user_id,created_at) VALUES(?,?,?)",
                normalizedUrl, userId, nowString());
    }

    /**
     * 判断用户是否可访问资源；存量无元数据文件返回 true 以保持升级兼容。
     */
    public boolean canAccessFileAsset(String url, String userId, boolean admin) {
        String normalizedUrl = normalizeAssetUrl(url);
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT owner_user_id FROM t_file_asset WHERE url=?", String.class, normalizedUrl);
        if (owners.isEmpty() || admin || (userId != null && userId.equals(owners.get(0)))) return true;
        if (userId == null) return false;
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_file_asset_grant WHERE url=? AND user_id=?",
                Integer.class, normalizedUrl, userId);
        return grants != null && grants > 0;
    }

    /** 去掉资源 URL 查询串，确保 shareId 等临时参数不能绕过所有权校验。 */
    private String normalizeAssetUrl(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    // ========== 系统公告（恒走 SQLite，与存储模式开关无关） ==========

    /** 公告行映射器 */
    private final RowMapper<Announcement> announcementRowMapper = (ResultSet rs, int rowNum) -> {
        Announcement a = new Announcement();
        a.setId(rs.getString("id"));
        a.setTitle(rs.getString("title"));
        a.setContent(rs.getString("content"));
        a.setStartAt(rs.getString("start_at"));
        a.setEndAt(rs.getString("end_at"));
        a.setEnabled(rs.getInt("enabled") == 1);
        a.setCreatedAt(rs.getString("created_at"));
        a.setUpdatedAt(rs.getString("updated_at"));
        return a;
    };

    /**
     * 新增公告记录（自动生成ID与创建/更新时间）
     * @param a 公告记录
     * @return 保存后的记录
     */
    public Announcement addAnnouncement(Announcement a) {
        if (a.getId() == null || a.getId().isEmpty()) {
            a.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (a.getCreatedAt() == null) {
            a.setCreatedAt(nowString());
        }
        if (a.getUpdatedAt() == null) {
            a.setUpdatedAt(a.getCreatedAt());
        }
        jdbcTemplate.update(
                "INSERT INTO t_announcement (id, title, content, start_at, end_at, enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                a.getId(), a.getTitle(), a.getContent(), a.getStartAt(), a.getEndAt(), a.isEnabled() ? 1 : 0, a.getCreatedAt(), a.getUpdatedAt());
        enabledAnnouncementCache = null;
        return a;
    }

    /**
     * 根据ID获取公告记录
     * @param id 公告ID
     * @return 公告记录，不存在返回 null
     */
    public Announcement getAnnouncementById(String id) {
        List<Announcement> list = jdbcTemplate.query(
                "SELECT * FROM t_announcement WHERE id = ?", announcementRowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 获取全部公告记录（含历史公告，最近更新在前）
     * @return 公告列表
     */
    public List<Announcement> getAllAnnouncements() {
        return jdbcTemplate.query(
                "SELECT * FROM t_announcement ORDER BY updated_at DESC, created_at DESC", announcementRowMapper);
    }

    /**
     * 获取当前启用的公告（最多一条；失效式缓存，登录/聊天页高频拉取不再每次查库）
     * @return 启用中的公告，无则返回 null
     */
    public Announcement getEnabledAnnouncement() {
        Optional<Announcement> cached = enabledAnnouncementCache;
        if (cached == null) {
            List<Announcement> list = jdbcTemplate.query(
                    "SELECT * FROM t_announcement WHERE enabled = 1 ORDER BY updated_at DESC LIMIT 1", announcementRowMapper);
            cached = list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
            enabledAnnouncementCache = cached;
        }
        return cached.orElse(null);
    }

    /**
     * 更新公告标题、内容与公告期（重新生效/改期时刷新 updated_at）
     * @param a 公告记录（需包含 id）
     */
    public void updateAnnouncement(Announcement a) {
        jdbcTemplate.update(
                "UPDATE t_announcement SET title = ?, content = ?, start_at = ?, end_at = ?, enabled = ?, updated_at = ? WHERE id = ?",
                a.getTitle(), a.getContent(), a.getStartAt(), a.getEndAt(), a.isEnabled() ? 1 : 0, a.getUpdatedAt(), a.getId());
        enabledAnnouncementCache = null;
    }

    /**
     * 下线除指定ID外的全部公告（保证同一时刻最多一条启用）
     * @param exceptId 保留启用的公告ID
     */
    public void disableOtherAnnouncements(String exceptId) {
        jdbcTemplate.update("UPDATE t_announcement SET enabled = 0 WHERE id <> ?", exceptId);
        enabledAnnouncementCache = null;
    }

    /**
     * 删除公告记录
     * @param id 公告ID
     * @return true=删除成功
     */
    public boolean deleteAnnouncement(String id) {
        boolean deleted = jdbcTemplate.update("DELETE FROM t_announcement WHERE id = ?", id) > 0;
        enabledAnnouncementCache = null;
        return deleted;
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
