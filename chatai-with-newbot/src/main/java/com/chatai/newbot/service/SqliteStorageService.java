package com.chatai.newbot.service;

import com.chatai.newbot.exception.ChatSyncConflictException;
import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
    private final ChatShareRepository chatShareRepository;
    private final SystemSettingsRepository settingsRepository;
    private final TokenRepository tokenRepository;
    private final FileAssetRepository fileAssetRepository;
    private final AnnouncementRepository announcementRepository;
    private final UsageLogRepository usageLogRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final UserRepository userRepository;
    private final SqliteSchemaInitializer schemaInitializer;
    private final ProviderCatalogRepository providerCatalogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // IP 注册计数（内存缓存，持久化到 t_setting）
    private Map<String, Map<String, Integer>> ipRegisterMap = new ConcurrentHashMap<>();

    // ========== 内存缓存（读多写少的小数据，减少高频 SQL 查询） ==========

    private static final String ADMIN_USERNAME = "admin";
    /** 首次安装内置 admin 的兜底密码：仅当未通过环境变量/系统属性指定时使用的回退值。
     *  优先读取 CHATAI_ADMIN_PASSWORD 环境变量或 chatai.admin.password 系统属性（部署时显式指定），
     *  都未配置时使用本兜底值并输出醒目告警，提示管理员立即修改 */
    private static final String ADMIN_FALLBACK_PASSWORD = "admin123";

    /**
     * 创建 SQLite 存储服务；该兼容构造器供非 Spring 测试与嵌入式调用使用。
     */
    public SqliteStorageService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ChatShareRepository(jdbcTemplate), new SystemSettingsRepository(jdbcTemplate),
                new TokenRepository(jdbcTemplate), new FileAssetRepository(jdbcTemplate),
                new AnnouncementRepository(jdbcTemplate), new UsageLogRepository(jdbcTemplate),
                new ChatSessionRepository(jdbcTemplate), new ModelConfigRepository(jdbcTemplate),
                new UserRepository(jdbcTemplate));
    }

    /** 创建 SQLite 存储服务并注入已拆分的领域仓储。 */
    public SqliteStorageService(JdbcTemplate jdbcTemplate, ChatShareRepository chatShareRepository,
                                SystemSettingsRepository settingsRepository, TokenRepository tokenRepository,
                                FileAssetRepository fileAssetRepository,
                                AnnouncementRepository announcementRepository,
                                UsageLogRepository usageLogRepository,
                                ChatSessionRepository chatSessionRepository,
                                ModelConfigRepository modelConfigRepository,
                                UserRepository userRepository) {
        this(jdbcTemplate, chatShareRepository, settingsRepository, tokenRepository, fileAssetRepository,
                announcementRepository, usageLogRepository, chatSessionRepository, modelConfigRepository,
                userRepository, new SqliteSchemaInitializer(jdbcTemplate),
                new ProviderCatalogRepository(jdbcTemplate, settingsRepository, modelConfigRepository));
    }

    /** 创建 SQLite 存储服务并注入已拆分的领域仓储。 */
    @Autowired
    public SqliteStorageService(JdbcTemplate jdbcTemplate, ChatShareRepository chatShareRepository,
                                SystemSettingsRepository settingsRepository, TokenRepository tokenRepository,
                                FileAssetRepository fileAssetRepository,
                                AnnouncementRepository announcementRepository,
                                UsageLogRepository usageLogRepository,
                                ChatSessionRepository chatSessionRepository,
                                ModelConfigRepository modelConfigRepository,
                                UserRepository userRepository, SqliteSchemaInitializer schemaInitializer,
                                ProviderCatalogRepository providerCatalogRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatShareRepository = chatShareRepository;
        this.settingsRepository = settingsRepository;
        this.tokenRepository = tokenRepository;
        this.fileAssetRepository = fileAssetRepository;
        this.announcementRepository = announcementRepository;
        this.usageLogRepository = usageLogRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.userRepository = userRepository;
        this.schemaInitializer = schemaInitializer;
        this.providerCatalogRepository = providerCatalogRepository;
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
            schemaInitializer.createTables();

            // 全量加载 t_setting 到内存缓存（后续 getSetting 纯内存读取）
            settingsRepository.reload();

            // 存量明文 API Key 一次性加密升级
            schemaInitializer.encryptLegacyApiKeys();

            // 加载 classpath 内置厂商
            providerCatalogRepository.loadProviders();
            log.info("SQLite: 已加载 {} 个内置厂商", providerCatalogRepository.getBuiltInProviders().size());

            // 将历史固定 __custom__/custom 厂商迁移为稳定且唯一的厂商 ID。
            providerCatalogRepository.migrateLegacyCustomProviders();

            // 加载厂商显示名覆盖
            providerCatalogRepository.loadProviderNameOverrides();


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

    // ========== t_setting 键值操作（写穿内存缓存） ==========

    /**
     * 读取配置值（纯内存缓存读取，所有 t_setting 读写均经过本类，缓存与库始终一致）
     * @param key 配置键
     * @return 配置值，不存在返回 null
     */
    public String getSetting(String key) {
        return settingsRepository.get(key);
    }

    /**
     * 写入配置值（存在则更新，不存在则插入；同步更新内存缓存）
     * @param key 配置键
     * @param value 配置值
     */
    public synchronized void setSetting(String key, String value) {
        settingsRepository.set(key, value);
    }

    // ========== 用户相关 ==========

    /**
     * 确保内置 admin 用户存在。
     * 初始密码策略：优先读取 CHATAI_ADMIN_PASSWORD 环境变量 / chatai.admin.password 系统属性，
     * 都未配置时使用兜底弱密码并输出醒目告警（部署文档应提示尽快修改或改用环境变量注入强密码）。
     * 仅对全新安装的库生效，已有 admin 用户的库不会触碰其密码。
     */
    private void ensureAdminUser() {
        if (userRepository.countByUsername(ADMIN_USERNAME) == 0) {
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
            for (Provider p : providerCatalogRepository.getBuiltInProviders()) {
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
        userRepository.insert(u);
    }

    @Override
    public User authenticate(String username, String password) {
        User user = userRepository.findByUsername(username);
        if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
            return null;
        }
        // 旧版 SHA-256 哈希校验通过后透明升级为 BCrypt
        if (PasswordHasher.isLegacyHash(user.getPassword())) {
            user.setPassword(PasswordHasher.hash(password));
            userRepository.updatePassword(user.getId(), user.getPassword());
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
        if (userRepository.countByUsername(username) > 0) return null;
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
        userRepository.updateLoginInfo(userId, ip, browser);
    }

    @Override
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    public List<User> queryUsers(String keyword, int offset, int limit) {
        return userRepository.query(keyword, offset, limit);
    }

    @Override
    public int countUsers(String keyword) {
        return userRepository.count(keyword);
    }

    @Override
    public User getUserById(String id) {
        return userRepository.findById(id);
    }

    @Override
    public boolean deleteUser(String userId) {
        // admin 不可删除
        User user = getUserById(userId);
        if (user == null || "admin".equals(user.getRole())) return false;
        return userRepository.deleteCascade(userId);
    }

    @Override
    public void updateUser(User user) {
        userRepository.update(user);
    }

    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) return 1;
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) return 2;
        userRepository.updatePassword(userId, PasswordHasher.hash(newPassword));
        return 0;
    }

    /**
     * 启用双重验证并保存密钥密文与恢复码摘要。
     */
    @Override
    public boolean enableTwoFactor(String userId, String encryptedSecret, List<String> recoveryCodeHashes) {
        return userRepository.enableTwoFactor(userId, encryptedSecret, recoveryCodeHashes);
    }

    /**
     * 关闭双重验证并清除密钥、恢复码和验证码重放状态。
     */
    @Override
    public boolean disableTwoFactor(String userId) {
        return userRepository.disableTwoFactor(userId);
    }

    /**
     * 仅在新时间步大于已记录时间步时原子更新，拒绝验证码重放。
     */
    @Override
    public boolean claimTwoFactorStep(String userId, long step) {
        return userRepository.claimTwoFactorStep(userId, step);
    }

    /**
     * 在进程内串行读取并删除恢复码摘要，确保同一码只能成功一次。
     */
    @Override
    public synchronized boolean consumeRecoveryCode(String userId, String recoveryCodeHash) {
        return userRepository.consumeRecoveryCode(userId, recoveryCodeHash);
    }

    /**
     * 用新摘要列表整体替换恢复码，旧恢复码立即全部失效。
     */
    @Override
    public boolean replaceRecoveryCodes(String userId, List<String> recoveryCodeHashes) {
        return userRepository.replaceRecoveryCodes(userId, recoveryCodeHashes);
    }

    // ========== 模型配置相关 ==========

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        return modelConfigRepository.findAll();
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
        providerCatalogRepository.fillProviderInfo(config);
        modelConfigRepository.insert(config);
        return config;
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        providerCatalogRepository.fillProviderInfo(config);
        modelConfigRepository.update(config);
    }

    @Override
    public boolean deleteModelConfig(String id) {
        boolean deleted = modelConfigRepository.delete(id);
        if (deleted) {
            // 删除的是默认模型则清空默认设置
            String defId = getDefaultModelId();
            if (id.equals(defId)) {
                clearDefaultModelId();
            }
        }
        return deleted;
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

    /** 委托厂商仓储执行 getAllProviders，保留原调用入口。 */
    public List<Provider> getAllProviders() {
        return providerCatalogRepository.getAllProviders();
    }

    /** 委托厂商仓储执行 getResolvedProvider，保留原调用入口。 */
    public Provider getResolvedProvider(String providerId) {
        return providerCatalogRepository.getResolvedProvider(providerId);
    }

    /** 委托厂商仓储执行 getProvider，保留原调用入口。 */
    public Provider getProvider(String providerId) {
        return providerCatalogRepository.getProvider(providerId);
    }

    /** 委托厂商仓储执行 getProviderDisplayName，保留原调用入口。 */
    public String getProviderDisplayName(String providerId) {
        return providerCatalogRepository.getProviderDisplayName(providerId);
    }

    /** 委托厂商仓储执行 renameProvider，保留原调用入口。 */
    public int renameProvider(String providerId, String newName, String newIcon, String oldName) {
        return providerCatalogRepository.renameProvider(providerId, newName, newIcon, oldName);
    }

    /** 委托厂商仓储执行 listCustomProviders，保留原调用入口。 */
    public List<Map<String, Object>> listCustomProviders() {
        return providerCatalogRepository.listCustomProviders();
    }

    /** 委托厂商仓储执行 saveProviderModels，保留原调用入口。 */
    public void saveProviderModels(String providerId, List<ProviderModel> models) {
        providerCatalogRepository.saveProviderModels(providerId, models);
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

    @Override
    public void addUsageLog(UsageLog logEntry) {
        usageLogRepository.add(logEntry);
    }

    /**
     * 获取使用记录（带安全上限的全量兜底查询）。
     * 主查询路径均为分页接口（queryUsageLogs/aggregateUsageStats），
     * 本方法仅作兼容保留，仓储层限制最多返回最近 10000 条，
     * 防止数据量增长后一次性装载全部记录导致内存溢出。
     * @return 最近的使用记录列表（时间降序）
     */
    @Override
    public List<UsageLog> getAllUsageLogs() {
        return usageLogRepository.findRecent();
    }

    /**
     * 获取指定用户的使用记录（带安全上限，最近 10000 条）
     * @param userId 用户ID
     * @return 该用户最近的使用记录列表（时间降序）
     */
    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return usageLogRepository.findRecentByUser(userId);
    }

    @Override
    public int countUsageByUserAndDay(String userId, String day) {
        return usageLogRepository.countByUserAndDay(userId, day);
    }

    @Override
    public long sumTokensByUserAndDay(String userId, String day) {
        return usageLogRepository.sumTokensByUserAndDay(userId, day);
    }

    @Override
    public double sumCostCnyByUserAndDay(String userId, String day) {
        List<UsageLog> rows = usageLogRepository.findByUserAndDay(userId, day);
        double total = 0D;
        for (UsageLog row : rows) total += row.getCostCny() != null ? row.getCostCny() : calculateCostCny(row);
        return total;
    }

    @Override
    public void updateUsageLog(UsageLog updatedLog) {
        double cost = calculateCostCny(updatedLog);
        usageLogRepository.update(updatedLog, cost);
        updatedLog.setCostCny(cost);
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
        return usageLogRepository.findDates();
    }

    /**
     * 删除指定日期之前的使用记录（定期清理过期日志用）。
     * 范围比较替代 SUBSTR 列函数包裹，可命中 idx_usage_time 索引。
     * @param day 截止日期（yyyy-MM-dd，不含当天）
     * @return 删除条数
     */
    public int deleteUsageLogsBefore(String day) {
        return usageLogRepository.deleteBefore(day);
    }

    // ========== 使用记录查询下推（筛选/分页/聚合在 SQL 层完成） ==========

    @Override
    public List<UsageLog> queryUsageLogs(String username, String modelName, String startDate, String endDate,
                                         int offset, int limit) {
        return usageLogRepository.query(username, modelName, startDate, endDate, offset, limit);
    }

    @Override
    public int countUsageLogs(String username, String modelName, String startDate, String endDate) {
        return usageLogRepository.count(username, modelName, startDate, endDate);
    }

    @Override
    public Map<String, Long> summarizeUsage(String username, String startDate, String endDate) {
        return usageLogRepository.summarize(username, startDate, endDate);
    }

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName,
                                                         String startDate, String endDate) {
        return usageLogRepository.aggregate(usernames, modelName, startDate, endDate);
    }

    @Override
    public List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName,
                                                         String startDate, String endDate,
                                                         int offset, int limit) {
        return usageLogRepository.aggregate(usernames, modelName, startDate, endDate, offset, limit);
    }

    @Override
    public int countUsageStatGroups(List<String> usernames, String modelName,
                                    String startDate, String endDate) {
        return usageLogRepository.countGroups(usernames, modelName, startDate, endDate);
    }

    @Override
    public List<String> getUsageUsernames() {
        return usageLogRepository.findUsernames();
    }

    @Override
    public List<String> getUsageModelNames(String username) {
        return usageLogRepository.findModelNames(username);
    }

    // ========== 聊天记录（按会话行存储 + 旧整文档兼容） ==========

    /**
     * 加载用户聊天记录 JSON 文档（旧整文档表，仅作按会话行迁移来源与备份）
     * @param userId 用户ID
     * @return JSON 字符串，不存在返回 null
     */
    public String loadChatData(String userId) {
        return chatSessionRepository.loadLegacyData(userId);
    }

    /**
     * 删除用户聊天记录（整文档备份 + 按会话行 + 用户状态三张表一并清理）
     * @param userId 用户ID
     */
    public void deleteChatData(String userId) {
        chatSessionRepository.deleteUserData(userId);
    }

    /**
     * 判断用户是否已存在会话状态行（存在即表示已完成按会话行迁移）
     * @param userId 用户ID
     * @return true=已迁移
     */
    public boolean hasChatUserState(String userId) {
        return chatSessionRepository.hasUserState(userId);
    }

    /**
     * 加载用户会话全局状态（含同步序列号 sync_seq 与文件夹版本号 folders_version）
     * @param userId 用户ID
     * @return 含 last_chat_id、deleted_chat_ids、folders_json、sync_seq、folders_version 的记录，不存在返回 null
     */
    public Map<String, Object> loadChatUserState(String userId) {
        return chatSessionRepository.loadUserState(userId);
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
        chatSessionRepository.saveUserState(
                userId, lastChatId, deletedChatIdsJson, foldersJson, updatedAt, updatedAtTs);
    }

    /**
     * 查询用户会话数据的版本号（多端轻量变更检测）。
     * 使用服务端原子自增的同步序列号 sync_seq（每次成功保存 +1），
     * 不依赖客户端时钟，同一毫秒内多次保存也能产生新版本，避免漏更新。
     * @param userId 用户ID
     * @return 版本号（单调递增序列），无任何数据时返回 0
     */
    public long getChatHistoryVersion(String userId) {
        return chatSessionRepository.getHistoryVersion(userId);
    }

    /**
     * 原子递增用户会话同步序列号并返回新值（每次保存会话数据成功后调用）。
     * 调用方须处于该用户的同步锁与事务内，update+select 之间不会被其他写入插队。
     * @param userId 用户ID
     * @return 递增后的新序列号；状态行不存在时返回 0
     */
    public long bumpChatSyncSeq(String userId) {
        return chatSessionRepository.bumpSyncSequence(userId);
    }

    /**
     * 原子递增文件夹定义版本号并返回新值（文件夹定义变更时调用）。
     * @param userId 用户ID
     * @return 递增后的文件夹版本号
     */
    public long bumpChatFoldersVersion(String userId) {
        return chatSessionRepository.bumpFoldersVersion(userId);
    }

    /**
     * 加载用户全部会话行（含消息正文，导出/全文搜索等全量场景用；不含回收站已删会话）
     * @param userId 用户ID
     * @return 每条记录含 chat_id/messages/meta，按插入顺序返回
     */
    public List<Map<String, Object>> listChatSessions(String userId) {
        return chatSessionRepository.listSessions(userId);
    }

    /**
     * 加载用户全部会话摘要行（仅冗余摘要列 + 元信息 + 版本号，不含消息正文与回收站会话，侧边栏首屏用）
     * @param userId 用户ID
     * @return 每条记录含 chat_id/title/preview/last_time/msg_count/meta/version，按插入顺序返回
     */
    public List<Map<String, Object>> listChatSessionSummaries(String userId) {
        return chatSessionRepository.listSummaries(userId);
    }

    /**
     * 加载单个会话行（切换会话按需加载用；回收站中的会话返回不存在）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 含 messages/meta/version 的记录，不存在或已删除返回 null
     */
    public Map<String, Object> getChatSession(String userId, String chatId) {
        return chatSessionRepository.findSession(userId, chatId);
    }

    /**
     * 查询单个会话行的当前版本号（乐观锁校验用；回收站中的会话也返回，供同步判定"已删除"冲突）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 当前版本号，会话不存在返回 null
     */
    public Integer getChatSessionVersion(String userId, String chatId) {
        return chatSessionRepository.findVersion(userId, chatId);
    }

    /**
     * 查询会话行的软删除标记（回收站判定用）
     * @return deleted_at 时间字符串；不存在或未删除返回 null
     */
    public String getChatSessionDeletedAt(String userId, String chatId) {
        return chatSessionRepository.findDeletedAt(userId, chatId);
    }

    /**
     * 查询用户全部会话 ID（仅 ID 列，分享状态判定等存在性检查用，不加载消息正文；不含回收站）
     * @param userId 用户ID
     * @return 会话ID列表
     */
    public List<String> listChatSessionIds(String userId) {
        return chatSessionRepository.listSessionIds(userId);
    }

    // ========== 会话上下文摘要缓存 ==========

    /**
     * 读取会话上下文摘要（仅当覆盖范围与本次裁剪一致时才有效：
     * 编辑/重新生成/版本切换/清除上下文会改变历史前缀，coveredCount 不匹配即视为过期）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @param coveredCount 本次裁剪覆盖的历史条数
     * @return 有效摘要内容，无或过期返回 null
     */
    public String getChatContextSummary(String userId, String chatId, int coveredCount) {
        return chatSessionRepository.findContextSummary(userId, chatId, coveredCount);
    }

    /**
     * 写入会话上下文摘要（覆盖旧值；同一 chatId 仅保留最新覆盖范围的摘要）
     */
    public void saveChatContextSummary(String userId, String chatId, int coveredCount, String content) {
        chatSessionRepository.saveContextSummary(userId, chatId, coveredCount, content);
    }

    /**
     * 删除会话的上下文摘要（会话彻底删除/清除上下文时调用，防止过期摘要被继续使用）
     */
    public void deleteChatContextSummary(String userId, String chatId) {
        chatSessionRepository.deleteContextSummary(userId, chatId);
    }

    /**
     * 按关键字粗筛标题或消息正文命中的会话行（SQL LIKE 下推 + ESCAPE 字面量转义，跨会话全文搜索用）。
     * 仅做候选集收窄：消息 LIKE 命中的是 JSON 全文（可能误命中字段名等非内容文本），
     * 调用方需对标题和消息内容做精确二次匹配。
     * 关键字中的 %/_/\ 会被转义为字面量，保证参数化查询的字面匹配语义；
     * 支持 updated_at_ts 时间范围与文件夹归属（meta JSON 包含匹配）粗筛；
     * 按 updated_at_ts DESC + chat_id 稳定排序分页，不以固定候选上限截断旧结果。
     * @param userId 用户ID
     * @param keyword 关键字（字面量子串，无通配符语义）
     * @param batchLimit 本批候选会话数
     * @param batchOffset 候选偏移量（分页扫描用）
     * @param fromTs 起始时间戳（毫秒，可空）
     * @param toTs 结束时间戳（毫秒，可空）
     * @param folderId 文件夹归属筛选（可空）
     * @return 每批记录含 chat_id/title/messages，按 updated_at_ts 降序
     */
    public List<Map<String, Object>> searchChatSessionsByKeyword(String userId, String keyword,
                                                                  int batchLimit, int batchOffset,
                                                                  Long fromTs, Long toTs, String folderId) {
        return chatSessionRepository.search(
                userId, keyword, batchLimit, batchOffset, fromTs, toTs, folderId);
    }

    /**
     * 插入或更新单个会话行（无版本校验的兼容入口：旧数据迁移、备份恢复等内部场景使用，
     * 多端同步保存请使用 {@link #upsertChatSessionChecked}）。
     * @param userId 用户ID
     * @param chatId 会话ID
     * @param messagesJson 消息列表 JSON
     * @param metaJson 会话元信息 JSON（null 时保留原值）
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
        chatSessionRepository.upsert(userId, chatId, messagesJson, metaJson, title, preview,
                lastTime, msgCount, updatedAt, updatedAtTs);
    }

    /**
     * 带乐观版本校验的会话行写入（多端同步防覆盖的核心）。
     * expectedVersion 语义：null=旧客户端无版本概念，按原逻辑直接覆盖（兼容入口）；
     * 0=客户端认为是全新会话，服务端必须尚不存在该行；其余值必须等于服务端当前版本。
     * 版本不匹配或行状态与预期不符时抛出 {@link ChatSyncConflictException}，绝不静默覆盖。
     * 写入成功后版本号由服务端 +1（不依赖客户端时钟）。
     * @param expectedVersion 客户端基准版本（null/0/具体值，语义见上）
     * @return 写入后的新版本号
     */
    public long upsertChatSessionChecked(String userId, String chatId, String messagesJson, String metaJson,
                                          String title, String preview, String lastTime, int msgCount,
                                          String updatedAt, long updatedAtTs, Long expectedVersion) {
        return chatSessionRepository.upsertChecked(userId, chatId, messagesJson, metaJson, title, preview,
                lastTime, msgCount, updatedAt, updatedAtTs, expectedVersion);
    }

    /**
     * 带乐观版本校验的会话元信息更新（仅改 meta，不动消息正文）。
     * 用于置顶/重命名/文件夹归属等纯元信息变更，无需上传整个消息列表。
     * 会话行不存在时不创建幽灵行，直接返回 false（由调用方决定忽略或报冲突）。
     * @param expectedVersion 客户端基准版本（null 兼容旧逻辑直接覆盖）
     * @return true=更新成功；false=会话行不存在
     */
    public boolean updateChatSessionMetaChecked(String userId, String chatId, String metaJson,
                                                 Long expectedVersion, String updatedAt, long updatedAtTs) {
        return chatSessionRepository.updateMetaChecked(
                userId, chatId, metaJson, expectedVersion, updatedAt, updatedAtTs);
    }

    /**
     * 软删除用户的指定会话（回收站：标记 deleted_at，行保留可恢复）
     * @param userId 用户ID
     * @param chatIds 待删除的会话ID集合
     * @param deletedAt 删除时间字符串
     */
    public void softDeleteChatSessions(String userId, Collection<String> chatIds, String deletedAt) {
        chatSessionRepository.softDelete(userId, chatIds, deletedAt);
    }

    /**
     * 恢复回收站中的会话（清除软删除标记，版本号 +1 触发多端刷新）
     * @param userId 用户ID
     * @param chatIds 待恢复的会话ID集合
     * @return 实际恢复的会话ID列表（仅回收站中的会话可恢复）
     */
    public List<String> restoreChatSessions(String userId, Collection<String> chatIds) {
        return chatSessionRepository.restore(userId, chatIds);
    }

    /**
     * 彻底删除回收站中的会话行（不可恢复；仅允许删除已软删除的行，防止误删正常会话）
     * @param userId 用户ID
     * @param chatIds 待彻底删除的会话ID集合；null 或空列表表示清空该用户全部回收站
     * @return 删除行数
     */
    public int purgeChatSessions(String userId, Collection<String> chatIds) {
        return chatSessionRepository.purge(userId, chatIds);
    }

    /**
     * 清理保留期之外的回收站会话（每日定时任务用）
     * @param cutoff 删除时间下限（deleted_at 早于此时间的会话被彻底删除）
     * @return 删除行数
     */
    public int purgeTrashBefore(String cutoff) {
        return chatSessionRepository.purgeBefore(cutoff);
    }

    /**
     * 查询用户回收站会话列表（按删除时间倒序，含摘要列，不含消息正文）
     * @param userId 用户ID
     * @return 每条记录含 chat_id/title/preview/last_time/msg_count/deleted_at/version
     */
    public List<Map<String, Object>> listTrashSessions(String userId) {
        return chatSessionRepository.listTrash(userId);
    }

    /**
     * 删除用户的指定会话行（整户清理等内部场景物理删除）
     * @param userId 用户ID
     * @param chatIds 待删除的会话ID集合
     */
    public void deleteChatSessions(String userId, Collection<String> chatIds) {
        chatSessionRepository.deleteSessions(userId, chatIds);
    }

    /**
     * 加载全部聊天数据的原始 JSON 文本（含按会话行与旧整文档备份），
     * 供孤儿上传文件清理时正则提取附件引用，不做 JSON 解析避免解析失败遗漏引用
     * @return 原始 JSON 文本列表
     */
    public List<String> listAllChatPayloads() {
        return chatSessionRepository.listAllPayloads();
    }

    /**
     * 加载全部分享快照的原始 JSON 文本（孤儿上传文件清理时统计分享快照中的附件引用，
     * 防止源会话已删但分享仍有效的快照资源被误删）
     * @return 快照 JSON 文本列表
     */
    public List<String> listAllShareSnapshots() {
        return chatSessionRepository.listAllShareSnapshots();
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
        tokenRepository.insert(token, userId, ip, browser, expiresAt);
    }

    /**
     * 查询指定用户的所有登录 Token 记录（登录设备管理，新登录在前）
     * @param userId 用户ID
     * @return 每条记录含 token/ip/browser/created_at/expires_at
     */
    public List<Map<String, Object>> listTokensByUser(String userId) {
        return tokenRepository.listByUser(userId);
    }

    /**
     * 删除指定 Token
     * @param token token 字符串
     */
    public void deleteToken(String token) {
        tokenRepository.delete(token);
    }

    /**
     * 删除指定用户的所有 Token
     * @param userId 用户ID
     */
    public void deleteTokensByUser(String userId) {
        tokenRepository.deleteByUser(userId);
    }

    /**
     * 更新 Token 过期时间（滑动续期）
     * @param token token 字符串
     * @param expiresAt 新的过期时间戳（毫秒）
     */
    public void updateTokenExpiry(String token, long expiresAt) {
        tokenRepository.updateExpiry(token, expiresAt);
    }

    /**
     * 回填 Token 的浏览器信息（browser 列上线前登录的旧 token 值为空，
     * 当前设备访问登录管理时用本次请求的 UA 补齐）
     * @param token token 字符串
     * @param browser 浏览器/终端名称
     */
    public void updateTokenBrowser(String token, String browser) {
        tokenRepository.updateBrowser(token, browser);
    }

    /**
     * 删除所有已过期的 Token
     * @param now 当前时间戳（毫秒）
     * @return 删除条数
     */
    public int deleteExpiredTokens(long now) {
        return tokenRepository.deleteExpired(now);
    }

    /**
     * 加载所有未过期的 Token 记录（启动时恢复登录态）
     * @param now 当前时间戳（毫秒）
     * @return 每条记录含 token/user_id/ip/expires_at
     */
    public List<Map<String, Object>> loadActiveTokens(long now) {
        return tokenRepository.loadActive(now);
    }

    // ========== 会话分享（恒走 SQLite，与存储模式开关无关） ==========

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
        return chatShareRepository.insert(s);
    }

    /**
     * 根据分享码获取分享记录
     * @param id 分享码
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareById(String id) {
        return chatShareRepository.findById(id);
    }

    /**
     * 获取用户创建的所有分享记录（新建在前）
     * @param userId 用户ID
     * @return 分享列表
     */
    public List<ChatShare> getChatSharesByUser(String userId) {
        return chatShareRepository.findByUserId(userId);
    }

    /**
     * 获取全部用户的分享记录（新建在前，后台分享管理用）
     * @return 分享列表
     */
    public List<ChatShare> getAllChatShares() {
        return chatShareRepository.findAll();
    }

    /**
     * 查找某用户对某会话已有的分享记录（同一会话复用分享码，避免重复生成）
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 分享记录，不存在返回 null
     */
    public ChatShare getChatShareByChat(String userId, String chatId) {
        return chatShareRepository.findByUserIdAndChatId(userId, chatId);
    }

    /**
     * 删除分享记录（撤销只读链接）
     * @param id 分享码
     * @return true=删除成功
     */
    public boolean deleteChatShare(String id) {
        return chatShareRepository.deleteById(id);
    }

    /**
     * 更新分享记录的过期时间（重新分享时续期；null=永久有效）
     * @param id 分享码
     * @param expiresAt 新的过期时间，null=永久
     */
    public void updateChatShareExpiry(String id, String expiresAt) {
        chatShareRepository.updateExpiry(id, expiresAt);
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
     * 清空全部内存缓存并从数据库重建（系统备份恢复后调用）：
     * 设置、模型配置、公告与用户缓存全部失效，下次读取时从恢复后的数据库重新加载。
     */
    public void reloadCaches() {
        settingsRepository.reload();
        modelConfigRepository.invalidateCache();
        announcementRepository.invalidateCache();
        userRepository.invalidateCache();
        providerCatalogRepository.loadProviderNameOverrides();
        ipRegisterMap.clear();
        loadIpRegisterMap();
        log.info("SQLite 内存缓存已全部刷新");
    }

    /**
     * 更新复用分享码的快照、有效期、密码与访问上限，并重置访问次数。
     */
    public void updateChatShareDetails(ChatShare share) {
        chatShareRepository.updateDetails(share);
    }

    /**
     * 更新分享的安全设置（访问密码/次数上限/已用计数重置），不影响快照与有效期。
     * @param id 分享码
     * @param passwordHash 新密码摘要，null=关闭密码
     * @param passwordEnc 新密码密文（ENC: 前缀），null=关闭密码
     * @param maxViews 新访问上限，0=不限
     * @param resetAccessCount true=将已访问次数清零
     * @return true=更新成功
     */
    public boolean updateChatShareSecurity(String id, String passwordHash, String passwordEnc,
                                           int maxViews, boolean resetAccessCount) {
        return chatShareRepository.updateSecurity(id, passwordHash, passwordEnc, maxViews, resetAccessCount);
    }

    /**
     * 原子占用一次分享访问额度，避免并发访问越过 maxViews。
     * @param id 分享码
     * @return true=允许访问并已计数
     */
    public boolean claimChatShareAccess(String id) {
        return chatShareRepository.claimAccess(id);
    }

    // ========== 上传资源所有权 ==========

    /** 记录新上传资源的所有者。 */
    public void registerFileAsset(String url, String ownerUserId, String assetType) {
        fileAssetRepository.register(url, ownerUserId, assetType);
    }

    /** 为复制分享的用户授予既有资源访问权，不改变原始所有者。 */
    public void grantFileAssetAccess(String url, String userId) {
        fileAssetRepository.grant(url, userId);
    }

    /**
     * 判断用户是否可访问资源；存量无元数据文件返回 true 以保持升级兼容。
     */
    public boolean canAccessFileAsset(String url, String userId, boolean admin) {
        return fileAssetRepository.canAccess(url, userId, admin);
    }

    // ========== 系统公告（恒走 SQLite，与存储模式开关无关） ==========

    /**
     * 新增公告记录（自动生成ID与创建/更新时间）
     * @param a 公告记录
     * @return 保存后的记录
     */
    public Announcement addAnnouncement(Announcement a) {
        return announcementRepository.add(a);
    }

    /**
     * 根据ID获取公告记录
     * @param id 公告ID
     * @return 公告记录，不存在返回 null
     */
    public Announcement getAnnouncementById(String id) {
        return announcementRepository.findById(id);
    }

    /**
     * 获取全部公告记录（含历史公告，最近更新在前）
     * @return 公告列表
     */
    public List<Announcement> getAllAnnouncements() {
        return announcementRepository.findAll();
    }

    /**
     * 获取当前启用的公告（最多一条；失效式缓存，登录/聊天页高频拉取不再每次查库）
     * @return 启用中的公告，无则返回 null
     */
    public Announcement getEnabledAnnouncement() {
        return announcementRepository.findEnabled();
    }

    /**
     * 更新公告标题、内容与公告期（重新生效/改期时刷新 updated_at）
     * @param a 公告记录（需包含 id）
     */
    public void updateAnnouncement(Announcement a) {
        announcementRepository.update(a);
    }

    /**
     * 下线除指定ID外的全部公告（保证同一时刻最多一条启用）
     * @param exceptId 保留启用的公告ID
     */
    public void disableOtherAnnouncements(String exceptId) {
        announcementRepository.disableOthers(exceptId);
    }

    /**
     * 删除公告记录
     * @param id 公告ID
     * @return true=删除成功
     */
    public boolean deleteAnnouncement(String id) {
        return announcementRepository.delete(id);
    }

    /** 获取当前时间字符串 yyyy-MM-dd HH:mm:ss */
    private String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
