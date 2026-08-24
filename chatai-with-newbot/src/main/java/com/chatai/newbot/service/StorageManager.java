package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储管理器（门面）
 * 所有数据操作直接委托给 SqliteStorageService（JSON 存储通道已于 SQLite 稳定运行后移除）。
 * Token 管理为内存缓存 + SQLite 持久化，重启不丢登录态，带过期时间与滑动续期。
 */
@Service
public class StorageManager implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageManager.class);

    private final SqliteStorageService sqliteStorage;

    // ========== Token 管理（内存缓存 + SQLite 持久化） ==========

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

    public StorageManager(SqliteStorageService sqliteStorage) {
        this.sqliteStorage = sqliteStorage;
    }

    /**
     * 初始化：恢复持久化的登录 Token（清理过期 + 加载未过期到内存），
     * 并把 providers.json 的厂商显示名/图标变更同步到已存储模型
     * （原先在 GET 模型列表接口里顺带写回，存在读接口写副作用，已改为启动时一次性同步）
     */
    @PostConstruct
    public void init() {
        restoreTokens();
        syncProviderInfoToModels();
        log.info("存储模式: SQLite");
    }

    /**
     * 启动时同步已存储模型的厂商显示名/图标（预置厂商）。
     * 运行期的厂商改名由 renameProvider 实时同步，这里只处理 providers.json 文件级变更。
     * supportsThinking / supportsMultimodal 由管理员手动维护，不自动覆盖。
     */
    private void syncProviderInfoToModels() {
        try {
            int updated = 0;
            for (ModelConfig model : sqliteStorage.getAllModelConfigs()) {
                boolean needUpdate = false;
                // 同步厂商显示名（如有覆盖）
                String displayName = sqliteStorage.getProviderDisplayName(model.getProviderId());
                if (displayName != null && !displayName.equals(model.getProviderName())) {
                    model.setProviderName(displayName);
                    needUpdate = true;
                }
                // 同步厂商图标（来自 providers.json，预置厂商不可改）
                Provider rawProvider = sqliteStorage.getProvider(model.getProviderId());
                if (rawProvider != null && rawProvider.getIcon() != null
                        && !rawProvider.getIcon().equals(model.getProviderIcon())) {
                    model.setProviderIcon(rawProvider.getIcon());
                    needUpdate = true;
                }
                if (needUpdate) {
                    sqliteStorage.updateModelConfig(model);
                    updated++;
                }
            }
            if (updated > 0) log.info("启动时已同步 {} 个模型的厂商名/图标", updated);
        } catch (Exception e) {
            log.warn("启动时同步模型厂商信息失败（不影响启动）", e);
        }
    }

    // ========== 配置读写 ==========

    /**
     * 读取 SQLite t_setting 配置值（委托给 SqliteStorageService）
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    public String getSetting(String key) {
        return sqliteStorage.getSetting(key);
    }

    /** 写入 SQLite t_setting 配置值。 */
    public void setSetting(String key, String value) {
        sqliteStorage.setSetting(key, value);
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
        return sqliteStorage.getUserById(info.userId);
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

    // ========== 每日调用配额（存于 t_setting） ==========

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

    // ========== 安全设置（存于 t_setting） ==========

    /** IP绑定开关缓存（每次请求都会读取，避免频繁查库） */
    private volatile Boolean ipBindingEnabledCache;

    /**
     * IP绑定校验是否开启（登录后IP变更强制下线）
     * @return true=开启（默认开启）
     */
    public boolean getIpBindingEnabled() {
        Boolean cached = ipBindingEnabledCache;
        if (cached != null) return cached;
        boolean enabled;
        try {
            enabled = !"false".equals(sqliteStorage.getSetting("ip_binding_enabled"));
        } catch (Exception e) {
            enabled = true;
        }
        ipBindingEnabledCache = enabled;
        return enabled;
    }

    /**
     * 设置IP绑定校验开关
     * @param enabled 是否开启
     */
    public void setIpBindingEnabled(boolean enabled) {
        sqliteStorage.setSetting("ip_binding_enabled", enabled ? "true" : "false");
        ipBindingEnabledCache = enabled;
    }

    /** 获取注册总开关，未配置时默认允许注册以兼容现有部署。 */
    public boolean getRegistrationEnabled() {
        return !"false".equals(sqliteStorage.getSetting("registration_enabled"));
    }

    /** 设置注册总开关。 */
    public void setRegistrationEnabled(boolean enabled) {
        sqliteStorage.setSetting("registration_enabled", String.valueOf(enabled));
    }

    /** 获取注册验证码开关。 */
    public boolean getRegistrationCaptchaEnabled() {
        return "true".equals(sqliteStorage.getSetting("registration_captcha_enabled"));
    }

    /** 设置注册验证码开关。 */
    public void setRegistrationCaptchaEnabled(boolean enabled) {
        sqliteStorage.setSetting("registration_captcha_enabled", String.valueOf(enabled));
    }

    /** 判断是否已配置注册邀请码。 */
    public boolean hasRegistrationInviteCode() {
        String hash = sqliteStorage.getSetting("registration_invite_hash");
        return hash != null && !hash.isBlank();
    }

    /** 保存注册邀请码摘要；空字符串表示清除邀请码限制。 */
    public void setRegistrationInviteCode(String inviteCode) {
        String value = inviteCode == null ? "" : inviteCode.trim();
        sqliteStorage.setSetting("registration_invite_hash",
                value.isEmpty() ? null : PasswordHasher.hash(value));
    }

    /** 校验注册邀请码，未配置邀请码时直接通过。 */
    public boolean matchesRegistrationInviteCode(String inviteCode) {
        String hash = sqliteStorage.getSetting("registration_invite_hash");
        return hash == null || hash.isBlank()
                || PasswordHasher.matches(inviteCode == null ? "" : inviteCode.trim(), hash);
    }

    /** 获取 Bot 侧 SVG 头像源码，空字符串表示使用前端默认头像。 */
    public String getBotAvatarSvg() {
        String value = sqliteStorage.getSetting("bot_avatar_svg");
        return value == null ? "" : value;
    }

    /** 保存 Bot 侧 SVG 头像源码。 */
    public void setBotAvatarSvg(String svg) {
        sqliteStorage.setSetting("bot_avatar_svg", svg == null || svg.isBlank() ? null : svg);
    }

    // ========== 联网搜索配置（Tavily，存于 t_setting） ==========

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

    // ========== 公告配置（存于 t_announcement） ==========

    /** 公告时间格式 yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter ANNOUNCEMENT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 将旧版单条公告（t_setting 键值）迁移到 t_announcement（幂等，仅首次执行）
     */
    private void ensureAnnouncementMigrated() {
        try {
            String legacy = sqliteStorage.getSetting("announcement_content");
            if (legacy == null || legacy.trim().isEmpty()) return;
            if (!sqliteStorage.getAllAnnouncements().isEmpty()) return;
            Announcement a = new Announcement();
            a.setTitle("系统公告");
            a.setContent(legacy.trim());
            a.setEnabled(true);
            String legacyTime = sqliteStorage.getSetting("announcement_updated_at");
            if (legacyTime != null && !legacyTime.trim().isEmpty()) {
                a.setCreatedAt(legacyTime.trim());
                a.setUpdatedAt(legacyTime.trim());
            }
            sqliteStorage.addAnnouncement(a);
            // 清除旧键，避免重复迁移
            sqliteStorage.setSetting("announcement_content", "");
            sqliteStorage.setSetting("announcement_updated_at", "");
            log.info("已将旧版单条公告迁移到 t_announcement");
        } catch (Exception e) {
            log.warn("迁移旧版公告失败", e);
        }
    }

    /**
     * 获取全部公告记录（含历史公告，最近更新在前）
     * @return 公告列表
     */
    public List<Announcement> getAllAnnouncements() {
        ensureAnnouncementMigrated();
        return sqliteStorage.getAllAnnouncements();
    }

    /**
     * 根据ID获取公告记录
     * @param id 公告ID
     * @return 公告记录，不存在返回 null
     */
    public Announcement getAnnouncementById(String id) {
        return sqliteStorage.getAnnouncementById(id);
    }

    /**
     * 发布新公告（自动下线其它公告，保证同一时刻最多一条启用）
     * @param title 公告标题
     * @param content 公告正文
     * @param startAt 公告期开始时间，空=立即生效
     * @param endAt 公告期结束时间，空=长期有效
     * @return 保存后的公告记录
     */
    public Announcement publishAnnouncement(String title, String content, String startAt, String endAt) {
        ensureAnnouncementMigrated();
        Announcement a = new Announcement();
        a.setTitle(title == null ? "" : title.trim());
        a.setContent(content.trim());
        a.setStartAt(emptyToNull(startAt));
        a.setEndAt(emptyToNull(endAt));
        a.setEnabled(true);
        sqliteStorage.addAnnouncement(a);
        sqliteStorage.disableOtherAnnouncements(a.getId());
        return a;
    }

    /**
     * 重新生效/更改公告期：更新内容与公告期并启用，刷新 updatedAt（用户端会重新弹窗提醒），
     * 同时下线其它公告
     * @param id 公告ID
     * @param title 公告标题（null/空=不修改）
     * @param content 公告正文（null=不修改）
     * @param startAt 公告期开始时间，空=立即生效
     * @param endAt 公告期结束时间，空=长期有效
     * @return 更新后的公告记录，公告不存在返回 null
     */
    public Announcement republishAnnouncement(String id, String title, String content, String startAt, String endAt) {
        Announcement a = sqliteStorage.getAnnouncementById(id);
        if (a == null) return null;
        if (title != null && !title.trim().isEmpty()) {
            a.setTitle(title.trim());
        }
        if (content != null && !content.trim().isEmpty()) {
            a.setContent(content.trim());
        }
        a.setStartAt(emptyToNull(startAt));
        a.setEndAt(emptyToNull(endAt));
        a.setEnabled(true);
        a.setUpdatedAt(LocalDateTime.now().format(ANNOUNCEMENT_TIME_FMT));
        sqliteStorage.updateAnnouncement(a);
        sqliteStorage.disableOtherAnnouncements(id);
        return a;
    }

    /**
     * 下线公告（不删除记录，可在历史公告中重新生效）
     * @param id 公告ID
     * @return true=下线成功，false=公告不存在
     */
    public boolean offlineAnnouncement(String id) {
        Announcement a = sqliteStorage.getAnnouncementById(id);
        if (a == null) return false;
        a.setEnabled(false);
        sqliteStorage.updateAnnouncement(a);
        return true;
    }

    /**
     * 删除公告记录
     * @param id 公告ID
     * @return true=删除成功
     */
    public boolean deleteAnnouncement(String id) {
        return sqliteStorage.deleteAnnouncement(id);
    }

    /**
     * 获取当前对用户生效的公告（启用且处于公告期内）
     * @return 生效中的公告，无则返回 null
     */
    public Announcement getActiveAnnouncement() {
        ensureAnnouncementMigrated();
        Announcement a = sqliteStorage.getEnabledAnnouncement();
        if (a == null) return null;
        return isWithinPeriod(a) ? a : null;
    }

    /**
     * 判断公告当前是否处于公告期内（时间解析失败视为未设置该边界）
     * @param a 公告记录
     * @return true=公告期内
     */
    public boolean isWithinPeriod(Announcement a) {
        LocalDateTime now = LocalDateTime.now();
        if (a.getStartAt() != null && !a.getStartAt().isEmpty()) {
            try {
                if (now.isBefore(LocalDateTime.parse(a.getStartAt(), ANNOUNCEMENT_TIME_FMT))) return false;
            } catch (Exception ignore) {
                // 解析失败视为立即生效
            }
        }
        if (a.getEndAt() != null && !a.getEndAt().isEmpty()) {
            try {
                if (now.isAfter(LocalDateTime.parse(a.getEndAt(), ANNOUNCEMENT_TIME_FMT))) return false;
            } catch (Exception ignore) {
                // 解析失败视为长期有效
            }
        }
        return true;
    }

    /** 空白字符串归一化为 null（公告期边界未设置） */
    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    // ========== 委托方法：用户相关 ==========

    @Override
    public User authenticate(String username, String password) {
        return sqliteStorage.authenticate(username, password);
    }

    @Override
    public User register(String username, String password, String ip) {
        return sqliteStorage.register(username, password, ip);
    }

    @Override
    public boolean canRegisterFromIp(String ip) {
        return sqliteStorage.canRegisterFromIp(ip);
    }

    @Override
    public void updateLoginInfo(String userId, String ip, String browser) {
        sqliteStorage.updateLoginInfo(userId, ip, browser);
    }

    @Override
    public List<User> getAllUsers() {
        return sqliteStorage.getAllUsers();
    }

    @Override
    public List<User> queryUsers(String keyword, int offset, int limit) {
        return sqliteStorage.queryUsers(keyword, offset, limit);
    }

    @Override
    public int countUsers(String keyword) {
        return sqliteStorage.countUsers(keyword);
    }

    @Override
    public User getUserById(String id) {
        return sqliteStorage.getUserById(id);
    }

    @Override
    public boolean deleteUser(String userId) {
        return sqliteStorage.deleteUser(userId);
    }

    @Override
    public void updateUser(User user) {
        sqliteStorage.updateUser(user);
    }

    /**
     * 修改密码（委托后清除该用户所有 Token，强制重新登录）
     */
    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        int code = sqliteStorage.changePassword(userId, oldPassword, newPassword);
        if (code == 0) {
            removeTokensByUserId(userId);
        }
        return code;
    }

    /**
     * 移除指定用户除当前会话外的全部 Token，用于安全凭据变更后收敛既有登录面。
     * @param userId 用户ID
     * @param currentToken 当前操作会话 Token
     */
    public void removeOtherTokensByUserId(String userId, String currentToken) {
        List<String> tokensToRemove = activeTokens.entrySet().stream()
                .filter(entry -> userId.equals(entry.getValue().userId))
                .map(Map.Entry::getKey)
                .filter(token -> !token.equals(currentToken))
                .toList();
        tokensToRemove.forEach(this::removeToken);
    }

    /**
     * 启用用户双重验证。
     */
    @Override
    public boolean enableTwoFactor(String userId, String encryptedSecret, List<String> recoveryCodeHashes) {
        return sqliteStorage.enableTwoFactor(userId, encryptedSecret, recoveryCodeHashes);
    }

    /**
     * 关闭双重验证，并注销除当前操作会话外由控制器统一处理的登录状态。
     */
    @Override
    public boolean disableTwoFactor(String userId) {
        return sqliteStorage.disableTwoFactor(userId);
    }

    /**
     * 原子占用一个 TOTP 时间步。
     */
    @Override
    public boolean claimTwoFactorStep(String userId, long step) {
        return sqliteStorage.claimTwoFactorStep(userId, step);
    }

    /**
     * 一次性消费恢复码摘要。
     */
    @Override
    public boolean consumeRecoveryCode(String userId, String recoveryCodeHash) {
        return sqliteStorage.consumeRecoveryCode(userId, recoveryCodeHash);
    }

    /**
     * 替换全部恢复码摘要。
     */
    @Override
    public boolean replaceRecoveryCodes(String userId, List<String> recoveryCodeHashes) {
        return sqliteStorage.replaceRecoveryCodes(userId, recoveryCodeHashes);
    }

    // ========== 委托方法：模型配置相关 ==========

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        return sqliteStorage.getAllModelConfigs();
    }

    @Override
    public List<ModelConfig> getVisibleModels(User user) {
        return sqliteStorage.getVisibleModels(user);
    }

    @Override
    public ModelConfig getModelConfigById(String id) {
        return sqliteStorage.getModelConfigById(id);
    }

    @Override
    public ModelConfig addModelConfig(ModelConfig config) {
        return sqliteStorage.addModelConfig(config);
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        sqliteStorage.updateModelConfig(config);
    }

    @Override
    public boolean deleteModelConfig(String id) {
        return sqliteStorage.deleteModelConfig(id);
    }

    // ========== 委托方法：默认模型 ==========

    @Override
    public String getDefaultModelId() {
        return sqliteStorage.getDefaultModelId();
    }

    @Override
    public void setDefaultModelId(String modelId) {
        sqliteStorage.setDefaultModelId(modelId);
    }

    @Override
    public void clearDefaultModelId() {
        sqliteStorage.clearDefaultModelId();
    }

    // ========== 委托方法：厂商相关 ==========

    @Override
    public List<Provider> getAllProviders() {
        return sqliteStorage.getAllProviders();
    }

    @Override
    public Provider getProvider(String providerId) {
        return sqliteStorage.getProvider(providerId);
    }

    /**
     * 获取预置或自定义厂商的完整配置。
     * @param providerId 厂商唯一 ID
     * @return 厂商配置，不存在返回 null
     */
    public Provider getResolvedProvider(String providerId) {
        return sqliteStorage.getResolvedProvider(providerId);
    }

    @Override
    public String getProviderDisplayName(String providerId) {
        return sqliteStorage.getProviderDisplayName(providerId);
    }

    @Override
    public int renameProvider(String providerId, String newName, String newIcon, String oldName) {
        return sqliteStorage.renameProvider(providerId, newName, newIcon, oldName);
    }

    @Override
    public List<Map<String, Object>> listCustomProviders() {
        return sqliteStorage.listCustomProviders();
    }

    /**
     * 保存厂商支持的模型目录。
     * @param providerId 厂商唯一 ID
     * @param models 支持模型列表
     */
    public void saveProviderModels(String providerId, List<ProviderModel> models) {
        sqliteStorage.saveProviderModels(providerId, models);
    }

    // ========== 委托方法：使用记录相关 ==========

    @Override
    public void addUsageLog(UsageLog logEntry) {
        sqliteStorage.addUsageLog(logEntry);
    }

    @Override
    public List<UsageLog> getAllUsageLogs() {
        return sqliteStorage.getAllUsageLogs();
    }

    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return sqliteStorage.getUsageLogsByUser(userId);
    }

    @Override
    public int countUsageByUserAndDay(String userId, String day) {
        return sqliteStorage.countUsageByUserAndDay(userId, day);
    }

    @Override
    public long sumTokensByUserAndDay(String userId, String day) {
        return sqliteStorage.sumTokensByUserAndDay(userId, day);
    }

    @Override
    public double sumCostCnyByUserAndDay(String userId, String day) {
        return sqliteStorage.sumCostCnyByUserAndDay(userId, day);
    }

    /** 获取全局普通用户每日 Token 限额，0 表示不限制。 */
    public long getDailyTokenLimit() {
        try {
            String val = sqliteStorage.getSetting("daily_token_limit");
            return val == null || val.isEmpty() ? 0L : Math.max(0L, Long.parseLong(val));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 设置全局普通用户每日 Token 限额，0 表示不限制。 */
    public void setDailyTokenLimit(long limit) {
        sqliteStorage.setSetting("daily_token_limit", String.valueOf(Math.max(0L, limit)));
    }

    /** 获取全局普通用户每日人民币成本限额，0 表示不限制。 */
    public double getDailyCostLimitCny() {
        try {
            String val = sqliteStorage.getSetting("daily_cost_limit_cny");
            return val == null || val.isEmpty() ? 0D : Math.max(0D, Double.parseDouble(val));
        } catch (Exception e) {
            return 0D;
        }
    }

    /** 设置全局普通用户每日人民币成本限额，0 表示不限制。 */
    public void setDailyCostLimitCny(double limit) {
        sqliteStorage.setSetting("daily_cost_limit_cny", String.valueOf(Math.max(0D, limit)));
    }

    @Override
    public void updateUsageLog(UsageLog logEntry) {
        sqliteStorage.updateUsageLog(logEntry);
    }

    /** 按模型当前人民币单价计算一条使用记录的成本。 */
    public double calculateUsageCostCny(UsageLog logEntry) {
        return sqliteStorage.calculateCostCny(logEntry);
    }

    /** 返回 SQLite 是否可执行最小只读查询。 */
    public boolean isReady() {
        return sqliteStorage.isReady();
    }

    @Override
    public List<String> getUsageLogDates() {
        return sqliteStorage.getUsageLogDates();
    }

    @Override
    public List<UsageLog> queryUsageLogs(String username, String modelName, String startDate, String endDate, int offset, int limit) {
        return sqliteStorage.queryUsageLogs(username, modelName, startDate, endDate, offset, limit);
    }

    @Override
    public int countUsageLogs(String username, String modelName, String startDate, String endDate) {
        return sqliteStorage.countUsageLogs(username, modelName, startDate, endDate);
    }

    @Override
    public Map<String, Long> summarizeUsage(String username, String startDate, String endDate) {
        return sqliteStorage.summarizeUsage(username, startDate, endDate);
    }

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName, String startDate, String endDate) {
        return sqliteStorage.aggregateUsageStats(usernames, modelName, startDate, endDate);
    }

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName, String startDate, String endDate, int offset, int limit) {
        return sqliteStorage.aggregateUsageStats(usernames, modelName, startDate, endDate, offset, limit);
    }

    @Override
    public int countUsageStatGroups(List<String> usernames, String modelName, String startDate, String endDate) {
        return sqliteStorage.countUsageStatGroups(usernames, modelName, startDate, endDate);
    }

    @Override
    public List<String> getUsageUsernames() {
        return sqliteStorage.getUsageUsernames();
    }

    @Override
    public List<String> getUsageModelNames(String username) {
        return sqliteStorage.getUsageModelNames(username);
    }

    // ========== 会话分享 ==========

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

    /** 更新分享快照与访问规则。 */
    public void updateChatShareDetails(ChatShare share) {
        sqliteStorage.updateChatShareDetails(share);
    }

    /** 原子占用一次分享访问额度。 */
    public boolean claimChatShareAccess(String id) {
        return sqliteStorage.claimChatShareAccess(id);
    }

    /** 记录上传资源所有者。 */
    public void registerFileAsset(String url, String ownerUserId, String assetType) {
        sqliteStorage.registerFileAsset(url, ownerUserId, assetType);
    }

    /** 为复制分享的用户授予资源读取权。 */
    public void grantFileAssetAccess(String url, String userId) {
        sqliteStorage.grantFileAssetAccess(url, userId);
    }

    /** 判断用户是否可访问上传资源。 */
    public boolean canAccessFileAsset(String url, String userId, boolean admin) {
        return sqliteStorage.canAccessFileAsset(url, userId, admin);
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
