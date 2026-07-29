package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储管理器（门面 + 开关控制 + 数据迁移）
 * 根据 storageMode 开关，将所有数据操作委托给 JsonFileStorageService 或 SqliteStorageService。
 * Token 管理为内存缓存 + SQLite 持久化（两种存储模式共用，重启不丢登录态），
 * 带过期时间与滑动续期。
 * 管理员可通过后台页面实时切换存储模式，无需重启。
 */
@Service
public class StorageManager implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final JsonFileStorageService jsonStorage;
    private final SqliteStorageService sqliteStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 存储模式开关：false=JSON文件，true=SQLite */
    private volatile boolean useSqlite = false;

    // ========== Token 管理（内存缓存 + SQLite 持久化，两种模式通用） ==========

    /** Token 有效期：7 天 */
    private static final long TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    /** Token 内存记录：用户ID + 登录IP + 过期时间 */
    private static class TokenInfo {
        final String userId;
        final String ip;
        volatile long expiresAt;

        TokenInfo(String userId, String ip, long expiresAt) {
            this.userId = userId;
            this.ip = ip;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, TokenInfo> activeTokens = new ConcurrentHashMap<>(); // token -> TokenInfo

    public StorageManager(JsonFileStorageService jsonStorage, SqliteStorageService sqliteStorage) {
        this.jsonStorage = jsonStorage;
        this.sqliteStorage = sqliteStorage;
    }

    /**
     * 初始化：从 SQLite t_setting 读取上次的开关状态
     * 若 SQLite 表不存在或读取失败，自动回退到 JSON 模式。
     */
    @PostConstruct
    public void init() {
        // 恢复持久化的登录 Token（清理过期 + 加载未过期到内存）
        restoreTokens();
        try {
            String mode = sqliteStorage.getSetting("storage_mode");
            // 仅当管理员显式切换为 JSON 模式时才使用 JSON；否则一律默认使用 SQLite
            if ("json".equals(mode)) {
                this.useSqlite = false;
                log.info("存储模式: JSON文件（从上次配置恢复）");
                return;
            }
            // 默认使用 SQLite：首次启用时自动执行 JSON → SQLite 数据迁移，确保已有数据不丢失
            // （已迁移过则 migration_done=true，会跳过，不会重复迁移或影响现有数据）
            String migrationDone = sqliteStorage.getSetting("migration_done");
            if (!"true".equals(migrationDone)) {
                log.info("首次启用 SQLite 存储，自动执行 JSON → SQLite 数据迁移...");
                try {
                    Map<String, Object> stats = migrateJsonToSqlite();
                    log.info("自动数据迁移完成: {}", stats);
                } catch (Exception e) {
                    log.error("自动数据迁移失败（仍切换到 SQLite，可在后台手动重试迁移）", e);
                }
            }
            this.useSqlite = true;
            // 持久化存储模式为 sqlite
            try {
                sqliteStorage.setSetting("storage_mode", "sqlite");
            } catch (Exception ignored) {
                // 持久化失败不影响本次运行
            }
            log.info("存储模式: SQLite（默认）");
        } catch (Exception e) {
            this.useSqlite = false;
            log.warn("初始化存储模式失败，回退到JSON模式", e);
        }
    }

    /** 获取当前活跃的存储实现 */
    private StorageService active() {
        return useSqlite ? sqliteStorage : jsonStorage;
    }

    // ========== 开关控制 ==========

    /**
     * 当前是否使用 SQLite 存储
     * @return true=SQLite模式，false=JSON模式
     */
    public boolean isUseSqlite() {
        return useSqlite;
    }

    /**
     * 读取 SQLite t_setting 配置值（委托给 SqliteStorageService）
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    public String getSetting(String key) {
        return sqliteStorage.getSetting(key);
    }

    /**
     * 切换存储模式
     * @param enable true=切换到SQLite，false=切换到JSON
     */
    public void setUseSqlite(boolean enable) {
        this.useSqlite = enable;
        try {
            sqliteStorage.setSetting("storage_mode", enable ? "sqlite" : "json");
            log.info("存储模式已切换为: {}", enable ? "SQLite" : "JSON文件");
        } catch (Exception e) {
            log.error("持久化存储模式失败", e);
        }
    }

    // ========== Token 管理（内存缓存 + SQLite 持久化） ==========

    /**
     * 启动时从 SQLite 恢复未过期的 Token，服务重启后用户无需重新登录
     */
    private void restoreTokens() {
        try {
            long now = System.currentTimeMillis();
            int purged = sqliteStorage.deleteExpiredTokens(now);
            List<Map<String, Object>> rows = sqliteStorage.loadActiveTokens(now);
            for (Map<String, Object> row : rows) {
                String token = (String) row.get("token");
                String userId = (String) row.get("user_id");
                String ip = (String) row.get("ip");
                long expiresAt = ((Number) row.get("expires_at")).longValue();
                if (token != null && userId != null) {
                    activeTokens.put(token, new TokenInfo(userId, ip, expiresAt));
                }
            }
            log.info("已恢复 {} 个登录Token（清理过期 {} 个）", rows.size(), purged);
        } catch (Exception e) {
            log.warn("恢复登录Token失败，本次运行仅使用内存Token", e);
        }
    }

    /**
     * 创建登录 Token（内存 + SQLite 持久化，有效期 7 天）
     * @param userId 用户ID
     * @param ip 登录IP
     * @param browser 登录浏览器/终端
     * @return 生成的 token 字符串
     */
    public String createToken(String userId, String ip, String browser) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        String boundIp = (ip == null || ip.isEmpty()) ? null : ip;
        activeTokens.put(token, new TokenInfo(userId, boundIp, expiresAt));
        try {
            sqliteStorage.insertToken(token, userId, boundIp, browser, expiresAt);
        } catch (Exception e) {
            log.warn("持久化Token失败（不影响本次登录）", e);
        }
        return token;
    }

    /**
     * 根据 Token 获取用户（校验过期 + 滑动续期：剩余寿命不足一半时自动续满 7 天）
     * @param token token 字符串
     * @return 用户对象，无效/过期 token 返回 null
     */
    public User getUserByToken(String token) {
        if (token == null) return null;
        TokenInfo info = activeTokens.get(token);
        if (info == null) return null;
        long now = System.currentTimeMillis();
        if (now >= info.expiresAt) {
            removeToken(token);
            return null;
        }
        // 滑动续期：剩余寿命低于 TTL 一半时续期，避免每次请求都写库
        if (info.expiresAt - now < TOKEN_TTL_MS / 2) {
            info.expiresAt = now + TOKEN_TTL_MS;
            try {
                sqliteStorage.updateTokenExpiry(token, info.expiresAt);
            } catch (Exception e) {
                log.debug("Token续期持久化失败", e);
            }
        }
        return active().getUserById(info.userId);
    }

    /**
     * 获取 token 登录时绑定的 IP
     * @param token token 字符串
     * @return 绑定的IP，未绑定返回 null
     */
    public String getTokenIp(String token) {
        if (token == null) return null;
        TokenInfo info = activeTokens.get(token);
        return info == null ? null : info.ip;
    }

    /**
     * 移除 Token（注销登录，内存 + SQLite 同步删除）
     * @param token token 字符串
     */
    public void removeToken(String token) {
        if (token != null) {
            activeTokens.remove(token);
            try {
                sqliteStorage.deleteToken(token);
            } catch (Exception e) {
                log.debug("删除持久化Token失败", e);
            }
        }
    }

    /**
     * 移除指定用户的所有 Token（修改密码后强制重新登录）
     * @param userId 用户ID
     */
    public void removeTokensByUserId(String userId) {
        activeTokens.entrySet().removeIf(e -> userId.equals(e.getValue().userId));
        try {
            sqliteStorage.deleteTokensByUser(userId);
        } catch (Exception e) {
            log.debug("删除用户持久化Token失败", e);
        }
    }

    /**
     * 计算 token 的会话ID（SHA-256 摘要前 16 位十六进制）。
     * 登录设备管理对外只暴露会话ID，避免泄露其他设备的完整登录凭证。
     * @param token token 字符串
     * @return 会话ID，计算失败返回 null
     */
    public static String sessionIdOf(String token) {
        if (token == null) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查询用户当前所有登录会话（登录设备管理，新登录在前）
     * @param userId 用户ID
     * @param currentToken 当前请求的 token，用于标记“当前设备”
     * @param currentBrowser 当前请求的浏览器，用于回填 browser 列上线前登录的旧 token
     * @return 会话列表，每条含 sessionId/ip/browser/createdAt/expiresAt/current
     */
    public List<Map<String, Object>> listUserSessions(String userId, String currentToken, String currentBrowser) {
        List<Map<String, Object>> sessions = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            for (Map<String, Object> row : sqliteStorage.listTokensByUser(userId)) {
                String token = (String) row.get("token");
                Object expiresObj = row.get("expires_at");
                long expiresAt = expiresObj instanceof Number ? ((Number) expiresObj).longValue() : 0;
                // 跳过已过期或已不在内存中的无效会话
                if (token == null || expiresAt <= now || !activeTokens.containsKey(token)) continue;
                boolean current = token.equals(currentToken);
                String browser = (String) row.get("browser");
                // 旧版本登录的 token 没有浏览器信息：当前设备用本次请求的 UA 回填并持久化，
                // 避免“当前设备”显示为未知浏览器（其他设备无法追溯 UA，保持原样）
                if (current && (browser == null || browser.isEmpty()) && currentBrowser != null && !currentBrowser.isEmpty()) {
                    browser = currentBrowser;
                    try {
                        sqliteStorage.updateTokenBrowser(token, browser);
                    } catch (Exception ex) {
                        log.debug("回填Token浏览器信息失败", ex);
                    }
                }
                Map<String, Object> session = new HashMap<>();
                session.put("sessionId", sessionIdOf(token));
                session.put("ip", row.get("ip"));
                session.put("browser", browser);
                session.put("createdAt", row.get("created_at"));
                session.put("expiresAt", expiresAt);
                session.put("current", current);
                sessions.add(session);
            }
        } catch (Exception e) {
            log.warn("查询用户登录会话失败", e);
        }
        return sessions;
    }

    /**
     * 踢掉用户的指定登录会话（仅限本人的其他设备）
     * @param userId 用户ID
     * @param sessionId 会话ID（token 摘要）
     * @param currentToken 当前请求的 token，禁止踢掉自己
     * @return 0=成功，1=会话不存在，2=不能踢掉当前设备
     */
    public int kickSession(String userId, String sessionId, String currentToken) {
        if (sessionId == null || sessionId.isEmpty()) return 1;
        for (Map.Entry<String, TokenInfo> entry : activeTokens.entrySet()) {
            if (!userId.equals(entry.getValue().userId)) continue;
            if (!sessionId.equals(sessionIdOf(entry.getKey()))) continue;
            if (entry.getKey().equals(currentToken)) return 2;
            removeToken(entry.getKey());
            return 0;
        }
        return 1;
    }

    // ========== 每日调用配额（存于 t_setting，两种存储模式通用） ==========

    /**
     * 获取每用户每日调用上限
     * @return 上限次数，0=不限制
     */
    public int getDailyChatLimit() {
        try {
            String val = sqliteStorage.getSetting("daily_chat_limit");
            return (val == null || val.isEmpty()) ? 0 : Math.max(0, Integer.parseInt(val));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 设置每用户每日调用上限
     * @param limit 上限次数，0=不限制
     */
    public void setDailyChatLimit(int limit) {
        sqliteStorage.setSetting("daily_chat_limit", String.valueOf(Math.max(0, limit)));
    }

    /**
     * 获取每分钟请求上限（短时滑动窗口限流）
     * @return 每分钟上限，0=不限制
     */
    public int getRateLimitPerMinute() {
        try {
            String val = sqliteStorage.getSetting("rate_limit_per_minute");
            return (val == null || val.isEmpty()) ? 0 : Math.max(0, Integer.parseInt(val));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 设置每分钟请求上限
     * @param limit 每分钟上限，0=不限制
     */
    public void setRateLimitPerMinute(int limit) {
        sqliteStorage.setSetting("rate_limit_per_minute", String.valueOf(Math.max(0, limit)));
    }

    /**
     * 获取上下文最大携带消息条数（不含后端注入的 system 消息）
     * @return 最大条数，0=不限制
     */
    public int getContextMaxMessages() {
        try {
            String val = sqliteStorage.getSetting("context_max_messages");
            return (val == null || val.isEmpty()) ? 0 : Math.max(0, Integer.parseInt(val));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 设置上下文最大携带消息条数
     * @param max 最大条数，0=不限制
     */
    public void setContextMaxMessages(int max) {
        sqliteStorage.setSetting("context_max_messages", String.valueOf(Math.max(0, max)));
    }

    // ========== 联网搜索配置（Tavily，存于 t_setting，两种存储模式通用） ==========

    /**
     * 联网搜索全局开关是否开启
     * @return true=已开启
     */
    public boolean getWebSearchEnabled() {
        return "true".equals(sqliteStorage.getSetting("web_search_enabled"));
    }

    /**
     * 设置联网搜索全局开关
     * @param enabled 是否开启
     */
    public void setWebSearchEnabled(boolean enabled) {
        sqliteStorage.setSetting("web_search_enabled", enabled ? "true" : "false");
    }

    /**
     * 获取 Tavily API Key
     * @return API Key，未配置返回 null
     */
    public String getTavilyApiKey() {
        return sqliteStorage.getSetting("tavily_api_key");
    }

    /**
     * 设置 Tavily API Key
     * @param apiKey API Key（传空则清除）
     */
    public void setTavilyApiKey(String apiKey) {
        sqliteStorage.setSetting("tavily_api_key", apiKey == null ? "" : apiKey.trim());
    }

    // ========== 公告配置（存于 t_setting，两种存储模式通用） ==========

    /**
     * 获取公告内容
     * @return 公告正文，未设置返回 null
     */
    public String getAnnouncement() {
        return sqliteStorage.getSetting("announcement_content");
    }

    /**
     * 获取公告最后更新时间（yyyy-MM-dd HH:mm:ss）
     * @return 更新时间，未设置返回 null
     */
    public String getAnnouncementUpdatedAt() {
        return sqliteStorage.getSetting("announcement_updated_at");
    }

    /**
     * 设置公告内容，同时刷新更新时间（传空则清除公告）
     * @param content 公告正文
     */
    public void setAnnouncement(String content) {
        String trimmed = content == null ? "" : content.trim();
        sqliteStorage.setSetting("announcement_content", trimmed);
        sqliteStorage.setSetting("announcement_updated_at", trimmed.isEmpty() ? ""
                : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
    }

    // ========== 委托方法：用户相关 ==========

    @Override
    public User authenticate(String username, String password) {
        return active().authenticate(username, password);
    }

    @Override
    public User register(String username, String password, String ip) {
        return active().register(username, password, ip);
    }

    @Override
    public boolean canRegisterFromIp(String ip) {
        return active().canRegisterFromIp(ip);
    }

    @Override
    public void updateLoginInfo(String userId, String ip, String browser) {
        active().updateLoginInfo(userId, ip, browser);
    }

    @Override
    public List<User> getAllUsers() {
        return active().getAllUsers();
    }

    @Override
    public User getUserById(String id) {
        return active().getUserById(id);
    }

    @Override
    public boolean deleteUser(String userId) {
        return active().deleteUser(userId);
    }

    @Override
    public void updateUser(User user) {
        active().updateUser(user);
    }

    /**
     * 修改密码（委托后清除该用户所有 Token，强制重新登录）
     */
    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        int code = active().changePassword(userId, oldPassword, newPassword);
        if (code == 0) {
            removeTokensByUserId(userId);
        }
        return code;
    }

    // ========== 委托方法：模型配置相关 ==========

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        return active().getAllModelConfigs();
    }

    @Override
    public List<ModelConfig> getVisibleModels(User user) {
        return active().getVisibleModels(user);
    }

    @Override
    public ModelConfig getModelConfigById(String id) {
        return active().getModelConfigById(id);
    }

    @Override
    public ModelConfig addModelConfig(ModelConfig config) {
        return active().addModelConfig(config);
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        active().updateModelConfig(config);
    }

    @Override
    public boolean deleteModelConfig(String id) {
        return active().deleteModelConfig(id);
    }

    // ========== 委托方法：默认模型 ==========

    @Override
    public String getDefaultModelId() {
        return active().getDefaultModelId();
    }

    @Override
    public void setDefaultModelId(String modelId) {
        active().setDefaultModelId(modelId);
    }

    @Override
    public void clearDefaultModelId() {
        active().clearDefaultModelId();
    }

    // ========== 委托方法：厂商相关 ==========

    @Override
    public List<Provider> getAllProviders() {
        return active().getAllProviders();
    }

    @Override
    public Provider getProvider(String providerId) {
        return active().getProvider(providerId);
    }

    @Override
    public String getProviderDisplayName(String providerId) {
        return active().getProviderDisplayName(providerId);
    }

    @Override
    public int renameProvider(String providerId, String newName, String newIcon, String oldName) {
        return active().renameProvider(providerId, newName, newIcon, oldName);
    }

    @Override
    public List<Map<String, Object>> listCustomProviders() {
        return active().listCustomProviders();
    }

    // ========== 委托方法：使用记录相关 ==========

    @Override
    public void addUsageLog(UsageLog logEntry) {
        active().addUsageLog(logEntry);
    }

    @Override
    public List<UsageLog> getAllUsageLogs() {
        return active().getAllUsageLogs();
    }

    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return active().getUsageLogsByUser(userId);
    }

    @Override
    public int countUsageByUserAndDay(String userId, String day) {
        return active().countUsageByUserAndDay(userId, day);
    }

    @Override
    public long sumTokensByUserAndDay(String userId, String day) {
        return active().sumTokensByUserAndDay(userId, day);
    }

    @Override
    public void updateUsageLog(UsageLog logEntry) {
        active().updateUsageLog(logEntry);
    }

    @Override
    public List<String> getUsageLogDates() {
        return active().getUsageLogDates();
    }

    // ========== 会话分享（恒走 SQLite，两种存储模式通用） ==========

    /**
     * 新增会话分享记录
     * @param s 分享记录
     * @return 保存后的记录（含自动生成的分享码）
     */
    public ChatShare addChatShare(ChatShare s) {
        return sqliteStorage.addChatShare(s);
    }

    /**
     * 根据分享码获取分享记录
     * @param id 分享码
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareById(String id) {
        return sqliteStorage.getChatShareById(id);
    }

    /**
     * 获取用户创建的所有分享记录
     * @param userId 用户ID
     * @return 分享列表
     */
    public List<ChatShare> getChatSharesByUser(String userId) {
        return sqliteStorage.getChatSharesByUser(userId);
    }

    /**
     * 获取全部用户的分享记录（后台分享管理用）
     * @return 分享列表
     */
    public List<ChatShare> getAllChatShares() {
        return sqliteStorage.getAllChatShares();
    }

    /**
     * 查找某用户对某会话已有的分享记录
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareByChat(String userId, String chatId) {
        return sqliteStorage.getChatShareByChat(userId, chatId);
    }

    /**
     * 删除分享记录（撤销只读链接）
     * @param id 分享码
     * @return true=删除成功
     */
    public boolean deleteChatShare(String id) {
        return sqliteStorage.deleteChatShare(id);
    }

    /**
     * 更新分享记录的过期时间（重新分享可续期或改为永久）
     * @param id 分享码
     * @param expiresAt 过期时间（null 表示永久有效）
     */
    public void updateChatShareExpiry(String id, String expiresAt) {
        sqliteStorage.updateChatShareExpiry(id, expiresAt);
    }

    // ========== 数据迁移（JSON → SQLite） ==========

    /**
     * 一键迁移：将 JSON 文件中的全部数据写入 SQLite
     * 在事务中执行，失败则回滚，不影响 JSON 数据。
     * @return 迁移统计信息 Map（users, models, logs, chatHistories 数量）
     */
    public Map<String, Object> migrateJsonToSqlite() {
        log.info("===== 开始数据迁移: JSON → SQLite =====");
        Map<String, Object> stats = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            // 1. 迁移用户
            List<User> users = jsonStorage.getAllUsers();
            sqliteStorage.batchInsertUsers(users);
            stats.put("users", users.size());
            log.info("迁移用户: {} 条", users.size());

            // 2. 迁移模型配置
            List<ModelConfig> models = jsonStorage.getAllModelConfigs();
            sqliteStorage.batchInsertModelConfigs(models);
            stats.put("models", models.size());
            log.info("迁移模型配置: {} 条", models.size());

            // 3. 迁移使用记录
            List<UsageLog> logs = jsonStorage.getAllUsageLogs();
            sqliteStorage.batchInsertUsageLogs(logs);
            stats.put("logs", logs.size());
            log.info("迁移使用记录: {} 条", logs.size());

            // 4. 迁移默认模型ID
            String defaultModelId = jsonStorage.getDefaultModelId();
            if (defaultModelId != null) {
                sqliteStorage.setSetting("default_model_id", defaultModelId);
            }
            stats.put("defaultModelId", defaultModelId);

            // 5. 迁移厂商显示名覆盖（从 JSON 文件直接读取）
            migrateProviderNameOverrides();

            // 6. 迁移 IP 注册计数（从 JSON 文件直接读取）
            migrateIpRegister();

            // 7. 迁移聊天记录（从 data/chat_history/ 目录读取）
            int chatCount = migrateChatHistories();
            stats.put("chatHistories", chatCount);
            log.info("迁移聊天记录: {} 个用户", chatCount);

            // 8. 标记迁移完成
            sqliteStorage.setSetting("migration_done", "true");

            long elapsed = System.currentTimeMillis() - startTime;
            stats.put("elapsedMs", elapsed);
            log.info("===== 数据迁移完成，耗时 {}ms =====", elapsed);
        } catch (Exception e) {
            log.error("数据迁移失败", e);
            stats.put("error", e.getMessage());
            throw new RuntimeException("数据迁移失败: " + e.getMessage(), e);
        }
        return stats;
    }

    /** 迁移厂商显示名覆盖（读取 data/provider_names.json） */
    private void migrateProviderNameOverrides() {
        try {
            Path dataDir = Paths.get(System.getProperty("user.dir"), "data");
            File file = dataDir.resolve("provider_names.json").toFile();
            if (file.exists()) {
                Map<String, String> overrides = objectMapper.readValue(file,
                        new TypeReference<Map<String, String>>() {});
                if (overrides != null && !overrides.isEmpty()) {
                    sqliteStorage.setSetting("provider_name_overrides",
                            objectMapper.writeValueAsString(overrides));
                    log.info("迁移厂商显示名覆盖: {} 条", overrides.size());
                }
            }
        } catch (Exception e) {
            log.warn("迁移厂商显示名覆盖失败（非致命）", e);
        }
    }

    /** 迁移 IP 注册计数（读取 data/ip_register.json） */
    private void migrateIpRegister() {
        try {
            Path dataDir = Paths.get(System.getProperty("user.dir"), "data");
            File file = dataDir.resolve("ip_register.json").toFile();
            if (file.exists()) {
                // 显式以 UTF-8 读取，避免使用平台默认字符集（Windows 下为 GBK）导致内容损坏
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                sqliteStorage.setSetting("ip_register", content);
                log.info("迁移IP注册计数完成");
            }
        } catch (Exception e) {
            log.warn("迁移IP注册计数失败（非致命）", e);
        }
    }

    /**
     * 迁移聊天记录（读取 data/chat_history/*.json）
     * 与 JSON 模式读取逻辑保持一致：按 userId 合并主文件（userId.json）与归档文件
     * （userId_时间戳.json），并剔除 deletedChatIds 中已删除的会话，最终以 UTF-8 写入 SQLite。
     * 仅迁移现存用户（SQLite 用户表）的聊天记录；无对应用户的孤儿文件将被忽略。
     * 注意：必须显式使用 UTF-8 读取文件，否则在 Windows（默认 GBK）下中文会变成乱码。
     * @return 成功迁移的用户数量
     */
    private int migrateChatHistories() {
        int count = 0;
        try {
            Path chatDir = Paths.get(System.getProperty("user.dir"), "data", "chat_history");
            File dir = chatDir.toFile();
            if (!dir.exists()) return 0;

            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) return 0;

            // 按 userId 分组：userId.json 为主文件，userId_时间戳.json 为归档文件
            // 用户ID为 UUID（只含连字符不含下划线），故取首个下划线前的部分作为 userId
            Map<String, List<File>> filesByUser = new LinkedHashMap<>();
            for (File file : files) {
                String name = file.getName();
                String base = name.substring(0, name.length() - ".json".length());
                int sep = base.indexOf('_');
                String userId = (sep > 0) ? base.substring(0, sep) : base;
                filesByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(file);
            }

            // 构建现存用户ID集合（取自 SQLite 用户表，迁移第1步已写入全部用户），用于过滤孤儿聊天记录
            Set<String> validUserIds = new HashSet<>();
            for (User u : sqliteStorage.getAllUsers()) {
                if (u != null && u.getId() != null) {
                    validUserIds.add(u.getId());
                }
            }

            for (Map.Entry<String, List<File>> entry : filesByUser.entrySet()) {
                String userId = entry.getKey();
                // 校验：聊天记录对应的用户必须现存（SQLite 用户表），否则忽略该孤儿文件
                if (!validUserIds.contains(userId)) {
                    log.info("忽略孤儿聊天记录文件（无对应用户）: userId={}", userId);
                    continue;
                }
                try {
                    Map<String, Object> merged = mergeChatHistoryFiles(userId, entry.getValue());
                    if (merged == null) continue;
                    String json = objectMapper.writeValueAsString(merged);
                    long updatedAtTs = ((Number) merged.get("updatedAtTs")).longValue();
                    String updatedAt = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(updatedAtTs), ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    sqliteStorage.saveChatData(userId, json, updatedAt, updatedAtTs);
                    count++;
                } catch (Exception e) {
                    log.warn("迁移聊天记录失败: userId={}", userId, e);
                }
            }
        } catch (Exception e) {
            log.warn("迁移聊天记录失败（非致命）", e);
        }
        return count;
    }

    /**
     * 合并同一用户的多个聊天记录文件（主文件 + 归档文件），逻辑与 JSON 模式加载保持一致。
     * 按文件最后修改时间升序合并 chats，剔除 deletedChatIds 中的会话，并取最新文件的 lastChatId。
     * @param userId 用户ID
     * @param files 该用户的全部聊天记录文件
     * @return 合并后的会话数据 Map（含 userId、lastChatId、chats、updatedAtTs），无有效数据时返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeChatHistoryFiles(String userId, List<File> files) {
        // 按最后修改时间升序排序，保证后写入的文件覆盖先写入的同名会话
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingLong(File::lastModified));

        Map<String, Object> mergedChats = new LinkedHashMap<>();
        String lastChatId = null;
        long latestUpdateTime = 0;

        for (File file : sorted) {
            try {
                // 显式以 UTF-8 读取，避免平台默认字符集（Windows 下 GBK）导致中文乱码
                String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Map<String, Object> data = objectMapper.readValue(content,
                        new TypeReference<Map<String, Object>>() {});

                Map<String, Object> fileChats = (Map<String, Object>) data.get("chats");
                if (fileChats != null) {
                    mergedChats.putAll(fileChats);
                }
                List<String> deletedIds = (List<String>) data.get("deletedChatIds");
                if (deletedIds != null) {
                    for (String deletedId : deletedIds) {
                        mergedChats.remove(deletedId);
                    }
                }
                String fileLastChatId = (String) data.get("lastChatId");
                Object updatedAtObj = data.get("updatedAtTs");
                long fileUpdateTime = (updatedAtObj instanceof Number)
                        ? ((Number) updatedAtObj).longValue() : 0L;
                if (fileUpdateTime >= latestUpdateTime) {
                    latestUpdateTime = fileUpdateTime;
                    lastChatId = fileLastChatId;
                }
            } catch (Exception e) {
                log.warn("读取聊天记录文件失败: {}", file.getName(), e);
            }
        }

        // 无有效会话数据则跳过，避免写入空记录
        if (mergedChats.isEmpty()) {
            return null;
        }

        // updatedAtTs 取文件内最新时间戳；缺失时回退为最新文件的修改时间
        long updatedAtTs = latestUpdateTime > 0 ? latestUpdateTime
                : sorted.get(sorted.size() - 1).lastModified();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("lastChatId", lastChatId);
        result.put("chats", mergedChats);
        result.put("updatedAtTs", updatedAtTs);
        return result;
    }

    /**
     * 获取 SQLite 数据库文件大小（人类可读格式）
     * @return 文件大小字符串，如 "2.3MB"；文件不存在返回 "0B"
     */
    public String getDbFileSize() {
        try {
            Path dbPath = Paths.get(System.getProperty("user.dir"), "data", "chatai.db");
            File dbFile = dbPath.toFile();
            if (!dbFile.exists()) return "0B";
            long bytes = dbFile.length();
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        } catch (Exception e) {
            return "未知";
        }
    }
}
