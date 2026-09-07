package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/** 负责 SQLite 建表、版本兼容迁移与历史密钥加密。 */
@Component
public class SqliteSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaInitializer.class);
    private final JdbcTemplate jdbcTemplate;

    /** 创建共享数据源上的数据库结构初始化器。 */
    public SqliteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建所有数据表（幂等操作）
     */
    public void createTables() {
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
        // 老数据库补充上下文容量列（幂等迁移，0/NULL=未设置按默认 32000）
        ensureColumn("t_model_config", "context_window", "INTEGER DEFAULT 0");

        // 会话上下文摘要缓存（纯服务端缓存，不参与多端同步）：
        // 摘要与原始会话分离保存，covered_count 记录覆盖范围，历史前缀变化即失效重建
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_context_summary (" +
                "user_id TEXT NOT NULL," +
                "chat_id TEXT NOT NULL," +
                "covered_count INTEGER DEFAULT 0," +
                "content TEXT," +
                "updated_at TEXT," +
                "PRIMARY KEY (user_id, chat_id)" +
                ")");

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
                "version INTEGER NOT NULL DEFAULT 1," +
                "deleted_at TEXT," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0," +
                "PRIMARY KEY (user_id, chat_id)" +
                ")");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_user ON t_chat_session(user_id)");
        // 老数据库补充会话行版本号列（幂等迁移，多端同步乐观锁）
        ensureColumn("t_chat_session", "version", "INTEGER NOT NULL DEFAULT 1");
        // 老数据库补充软删除标记列（幂等迁移，回收站功能：NULL=正常，非空=已删除时间）
        ensureColumn("t_chat_session", "deleted_at", "TEXT");

        // 用户会话全局状态（最后所在会话 + 已删除会话ID累积 + 会话文件夹定义；行存在即表示该用户已完成按会话行迁移）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_chat_user_state (" +
                "user_id TEXT PRIMARY KEY," +
                "last_chat_id TEXT," +
                "deleted_chat_ids TEXT," +
                "folders_json TEXT," +
                "sync_seq INTEGER DEFAULT 0," +
                "folders_version INTEGER DEFAULT 0," +
                "updated_at TEXT," +
                "updated_at_ts INTEGER DEFAULT 0" +
                ")");
        // 老数据库补充会话文件夹列（幂等迁移）
        ensureChatUserStateFoldersColumn();
        // 老数据库补充同步序列号与文件夹版本号列（幂等迁移，多端同步版本校验）
        ensureColumn("t_chat_user_state", "sync_seq", "INTEGER DEFAULT 0");
        ensureColumn("t_chat_user_state", "folders_version", "INTEGER DEFAULT 0");
        // sync_seq 从既有 updated_at_ts 初始化，避免版本号相对旧客户端回退后引发误判
        jdbcTemplate.execute("UPDATE t_chat_user_state SET sync_seq = MAX(updated_at_ts, 1) WHERE sync_seq = 0");

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
                "password_enc TEXT," +
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

    /**
     * 存量明文 API Key 一次性加密升级（幂等迁移）。
     * 扫描 t_model_config 中无 ENC: 前缀的明文 Key，加密后写回；已是密文则跳过。
     */
    public void encryptLegacyApiKeys() {
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
        ensureColumn("t_chat_share", "password_enc", "TEXT");
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

}
