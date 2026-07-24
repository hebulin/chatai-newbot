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
 * Token 管理为内存共享（两种模式共用），不随存储切换而丢失。
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

    // ========== Token 管理（内存共享，两种模式通用） ==========
    private final Map<String, String> activeTokens = new ConcurrentHashMap<>(); // token -> userId
    private final Map<String, String> tokenIps = new ConcurrentHashMap<>();     // token -> 登录时绑定的IP

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

    // ========== Token 管理（内存共享） ==========

    /**
     * 创建登录 Token
     * @param userId 用户ID
     * @param ip 登录IP
     * @return 生成的 token 字符串
     */
    public String createToken(String userId, String ip) {
        String token = UUID.randomUUID().toString().replace("-", "");
        activeTokens.put(token, userId);
        if (ip != null && !ip.isEmpty()) {
            tokenIps.put(token, ip);
        }
        return token;
    }

    /**
     * 根据 Token 获取用户
     * @param token token 字符串
     * @return 用户对象，无效 token 返回 null
     */
    public User getUserByToken(String token) {
        if (token == null) return null;
        String userId = activeTokens.get(token);
        if (userId == null) return null;
        return active().getUserById(userId);
    }

    /**
     * 获取 token 登录时绑定的 IP
     * @param token token 字符串
     * @return 绑定的IP，未绑定返回 null
     */
    public String getTokenIp(String token) {
        if (token == null) return null;
        return tokenIps.get(token);
    }

    /**
     * 移除 Token（注销登录）
     * @param token token 字符串
     */
    public void removeToken(String token) {
        if (token != null) {
            activeTokens.remove(token);
            tokenIps.remove(token);
        }
    }

    /**
     * 移除指定用户的所有 Token（修改密码后强制重新登录）
     * @param userId 用户ID
     */
    public void removeTokensByUserId(String userId) {
        activeTokens.entrySet().removeIf(e -> userId.equals(e.getValue()));
        // tokenIps 中对应的条目也一并清理
        tokenIps.keySet().removeIf(k -> !activeTokens.containsKey(k));
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
    public void updateUsageLog(UsageLog logEntry) {
        active().updateUsageLog(logEntry);
    }

    @Override
    public List<String> getUsageLogDates() {
        return active().getUsageLogDates();
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
