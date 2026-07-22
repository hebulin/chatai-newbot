package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JSON 文件存储实现 - 原有 FileStorageService 重构而来
 * 数据以 JSON 文件形式存储在 data/ 目录下。
 * Token 相关逻辑已抽到 StorageManager 中（两种模式共用内存 Token）。
 */
@Component
public class JsonFileStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStorageService.class);
    private final ObjectMapper objectMapper;
    private Path dataDir;

    // 内存缓存
    private List<User> users = Collections.synchronizedList(new ArrayList<>());
    private List<ModelConfig> modelConfigs = Collections.synchronizedList(new ArrayList<>());
    private List<Provider> providers = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> providerNameOverrides = new ConcurrentHashMap<>();
    private Map<String, List<UsageLog>> usageLogsByDay = new ConcurrentHashMap<>();
    private Map<String, Map<String, Integer>> ipRegisterMap = new ConcurrentHashMap<>();
    private volatile String defaultModelId = null;

    // 内置admin密码
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    public JsonFileStorageService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 初始化：加载所有 JSON 数据文件
     */
    @PostConstruct
    public void init() {
        try {
            String userDir = System.getProperty("user.dir");
            this.dataDir = Paths.get(userDir, "data");
            Files.createDirectories(dataDir);
            log.info("数据存储目录: {}", dataDir.toAbsolutePath());

            loadProviders();
            log.info("已加载 {} 个服务提供者", providers.size());
            loadProviderNameOverrides();
            log.info("已加载 {} 个厂商显示名覆盖", providerNameOverrides.size());
            loadUsers();
            log.info("已加载 {} 个用户", users.size());
            loadModelConfigs();
            log.info("已加载 {} 个模型配置", modelConfigs.size());
            loadUsageLogs();
            log.info("已加载 {} 天的使用日志，共 {} 条", usageLogsByDay.size(), getAllUsageLogs().size());
            loadIpRegisterMap();
            log.info("已加载 IP 注册计数");
            loadDefaultModel();

            ensureAdminUser();
            log.info("已确保admin用户存在");
        } catch (Exception e) {
            log.error("初始化JSON存储服务失败", e);
            throw new RuntimeException("初始化JSON存储服务失败", e);
        }
        log.info("JSON存储服务初始化完成");
    }

    // ========== 用户相关 ==========

    /** 确保内置 admin 用户存在 */
    private void ensureAdminUser() {
        Optional<User> adminOpt = users.stream()
                .filter(u -> ADMIN_USERNAME.equals(u.getUsername()))
                .findFirst();
        if (!adminOpt.isPresent()) {
            User admin = new User();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(hashPassword(ADMIN_DEFAULT_PASSWORD));
            admin.setRole("admin");
            admin.setCreatedAt(nowString());
            users.add(admin);
            saveUsers();
            log.info("已创建内置admin账户");
        }
    }

    @Override
    public User authenticate(String username, String password) {
        return users.stream()
                .filter(u -> u.getUsername().equals(username)
                        && u.getPassword().equals(hashPassword(password)))
                .findFirst()
                .orElse(null);
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
        if (users.stream().anyMatch(u -> u.getUsername().equals(username))) {
            return null;
        }
        if (ADMIN_USERNAME.equalsIgnoreCase(username)) {
            return null;
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPassword(hashPassword(password));
        user.setRole("user");
        user.setCreatedAt(nowString());
        user.setLastLoginIp(ip);
        users.add(user);
        saveUsers();

        String today = LocalDate.now().toString();
        Map<String, Integer> todayMap = ipRegisterMap.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        todayMap.merge(ip, 1, Integer::sum);
        saveIpRegisterMap();

        return user;
    }

    @Override
    public void updateLoginInfo(String userId, String ip, String browser) {
        users.stream()
                .filter(u -> u.getId().equals(userId))
                .findFirst()
                .ifPresent(u -> {
                    u.setLastLoginAt(nowString());
                    u.setLastLoginIp(ip);
                    u.setLastLoginBrowser(browser);
                    saveUsers();
                });
    }

    @Override
    public List<User> getAllUsers() {
        return new ArrayList<>(users);
    }

    @Override
    public User getUserById(String id) {
        return users.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(null);
    }

    @Override
    public boolean deleteUser(String userId) {
        boolean removed = users.removeIf(u -> u.getId().equals(userId) && !"admin".equals(u.getRole()));
        if (removed) saveUsers();
        return removed;
    }

    @Override
    public void updateUser(User user) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getId().equals(user.getId())) {
                users.set(i, user);
                saveUsers();
                return;
            }
        }
    }

    @Override
    public int changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) return 1;
        if (!user.getPassword().equals(hashPassword(oldPassword))) return 2;
        user.setPassword(hashPassword(newPassword));
        saveUsers();
        return 0;
    }

    // ========== 模型配置相关 ==========

    @Override
    public List<ModelConfig> getAllModelConfigs() {
        return new ArrayList<>(modelConfigs);
    }

    @Override
    public List<ModelConfig> getVisibleModels(User user) {
        return modelConfigs.stream()
                .filter(ModelConfig::isEnabled)
                .filter(m -> Boolean.TRUE.equals(m.getVisibleToAll())
                        || user.isAdmin()
                        || user.getAllowedModelIds().contains(m.getId()))
                .collect(Collectors.toList());
    }

    @Override
    public ModelConfig getModelConfigById(String id) {
        return modelConfigs.stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
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
        modelConfigs.add(config);
        saveModelConfigs();
        return config;
    }

    @Override
    public void updateModelConfig(ModelConfig config) {
        for (int i = 0; i < modelConfigs.size(); i++) {
            if (modelConfigs.get(i).getId().equals(config.getId())) {
                fillProviderInfo(config);
                modelConfigs.set(i, config);
                saveModelConfigs();
                return;
            }
        }
    }

    @Override
    public boolean deleteModelConfig(String id) {
        boolean removed = modelConfigs.removeIf(m -> m.getId().equals(id));
        if (removed) {
            if (id != null && id.equals(defaultModelId)) {
                clearDefaultModelId();
            }
            saveModelConfigs();
        }
        return removed;
    }

    /** 自动填充厂商信息（providerName、providerIcon、thinkingParamType） */
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

    /** 从 default_model.json 加载默认模型ID */
    private void loadDefaultModel() {
        File file = dataDir.resolve("default_model.json").toFile();
        if (file.exists()) {
            try {
                Map<String, Object> loaded = objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
                if (loaded != null) {
                    Object v = loaded.get("defaultModelId");
                    defaultModelId = (v == null || v.toString().isEmpty()) ? null : v.toString();
                }
            } catch (Exception e) {
                log.error("加载默认模型失败", e);
            }
        }
    }

    /** 保存默认模型ID到 default_model.json */
    private void saveDefaultModel() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("defaultModelId", defaultModelId);
        saveToFile("default_model.json", data);
    }

    @Override
    public String getDefaultModelId() {
        return defaultModelId;
    }

    @Override
    public synchronized void setDefaultModelId(String modelId) {
        this.defaultModelId = (modelId == null || modelId.isEmpty()) ? null : modelId;
        saveDefaultModel();
    }

    @Override
    public synchronized void clearDefaultModelId() {
        this.defaultModelId = null;
        saveDefaultModel();
    }

    // ========== 厂商相关 ==========

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
            int updated = 0;
            for (ModelConfig c : modelConfigs) {
                if ("__custom__".equals(c.getProviderId()) && targetOldName.equals(c.getProviderName())) {
                    boolean nameChanged = !trimmed.equals(c.getProviderName());
                    boolean iconChanged = !trimmedIcon.isEmpty() && !trimmedIcon.equals(c.getProviderIcon());
                    if (nameChanged) c.setProviderName(trimmed);
                    if (iconChanged) c.setProviderIcon(trimmedIcon);
                    if (nameChanged || iconChanged) updated++;
                }
            }
            if (updated > 0) saveModelConfigs();
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
        int updated = 0;
        for (ModelConfig c : modelConfigs) {
            if (providerId.equals(c.getProviderId()) && !trimmed.equals(c.getProviderName())) {
                c.setProviderName(trimmed);
                updated++;
            }
        }
        if (updated > 0) saveModelConfigs();
        return updated;
    }

    @Override
    public List<Map<String, Object>> listCustomProviders() {
        Map<String, int[]> nameCount = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> nameIconCount = new LinkedHashMap<>();
        for (ModelConfig c : modelConfigs) {
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

    // ========== 厂商显示名覆盖（持久化） ==========

    /** 从 provider_names.json 加载厂商显示名覆盖 */
    private void loadProviderNameOverrides() {
        File file = dataDir.resolve("provider_names.json").toFile();
        if (file.exists()) {
            try {
                Map<String, String> loaded = objectMapper.readValue(file,
                        new TypeReference<Map<String, String>>() {});
                if (loaded != null) {
                    providerNameOverrides.clear();
                    providerNameOverrides.putAll(loaded);
                }
            } catch (Exception e) {
                log.error("加载厂商显示名覆盖失败", e);
            }
        }
    }

    /** 保存厂商显示名覆盖到 provider_names.json */
    private void saveProviderNameOverrides() {
        saveToFile("provider_names.json", providerNameOverrides);
    }

    // ========== 使用记录相关（按天存储） ==========

    @Override
    public void addUsageLog(UsageLog logEntry) {
        String dayKey = logEntry.getTimestamp() != null ? logEntry.getTimestamp().substring(0, 10) : LocalDate.now().toString();
        List<UsageLog> dayList = usageLogsByDay.computeIfAbsent(dayKey, k -> Collections.synchronizedList(new ArrayList<>()));
        dayList.add(logEntry);
        saveUsageLogsByDay(dayKey);
    }

    @Override
    public List<UsageLog> getAllUsageLogs() {
        List<UsageLog> all = new ArrayList<>();
        for (List<UsageLog> dayList : usageLogsByDay.values()) {
            all.addAll(dayList);
        }
        return all;
    }

    @Override
    public List<UsageLog> getUsageLogsByUser(String userId) {
        return getAllUsageLogs().stream()
                .filter(l -> l.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

    @Override
    public void updateUsageLog(UsageLog updatedLog) {
        String dayKey = updatedLog.getTimestamp() != null ? updatedLog.getTimestamp().substring(0, 10) : null;
        if (dayKey == null) return;
        List<UsageLog> dayList = usageLogsByDay.get(dayKey);
        if (dayList == null) return;
        for (int i = 0; i < dayList.size(); i++) {
            if (dayList.get(i) == updatedLog) {
                saveUsageLogsByDay(dayKey);
                return;
            }
        }
        for (int i = 0; i < dayList.size(); i++) {
            UsageLog existing = dayList.get(i);
            if (existing.getUserId().equals(updatedLog.getUserId())
                    && existing.getTimestamp().equals(updatedLog.getTimestamp())
                    && existing.getModelId().equals(updatedLog.getModelId())) {
                dayList.set(i, updatedLog);
                saveUsageLogsByDay(dayKey);
                return;
            }
        }
    }

    @Override
    public List<String> getUsageLogDates() {
        return usageLogsByDay.keySet().stream().sorted().collect(Collectors.toList());
    }

    // ========== 文件读写 ==========

    /** 加载用户列表 */
    private void loadUsers() {
        users = loadList("users.json", new TypeReference<List<User>>() {});
    }

    /** 保存用户列表 */
    private void saveUsers() {
        saveToFile("users.json", users);
    }

    /** 加载模型配置列表（含旧数据兼容补全） */
    private void loadModelConfigs() {
        modelConfigs = loadList("models.json", new TypeReference<List<ModelConfig>>() {});
        boolean needSave = false;
        for (ModelConfig config : modelConfigs) {
            if (config.getProviderId() != null && !"custom".equals(config.getProviderId())) {
                Provider provider = providers.stream()
                        .filter(p -> p.getId().equals(config.getProviderId()))
                        .findFirst().orElse(null);
                if (provider != null) {
                    if (config.getProviderName() == null || config.getProviderName().isEmpty()) {
                        config.setProviderName(provider.getName());
                        needSave = true;
                    }
                    if (config.getProviderIcon() == null || config.getProviderIcon().isEmpty()) {
                        config.setProviderIcon(provider.getIcon());
                        needSave = true;
                    }
                    if (config.getThinkingParamType() == null || config.getThinkingParamType().isEmpty()) {
                        config.setThinkingParamType(provider.getThinkingParamType());
                        needSave = true;
                    }
                }
            }
        }
        if (needSave) {
            saveModelConfigs();
            log.info("已自动补全旧模型配置的厂商信息字段");
        }
    }

    /** 保存模型配置列表 */
    private void saveModelConfigs() {
        saveToFile("models.json", modelConfigs);
    }

    /** 从 classpath 加载内置厂商 providers.json */
    private void loadProviders() {
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("providers.json");
            if (is != null) {
                providers = Collections.synchronizedList(
                        objectMapper.readValue(is, new TypeReference<List<Provider>>() {}));
                log.info("加载了 {} 个内置厂商", providers.size());
            } else {
                log.warn("未找到 providers.json");
                providers = Collections.synchronizedList(new ArrayList<>());
            }
        } catch (Exception e) {
            log.error("加载厂商配置失败", e);
            providers = Collections.synchronizedList(new ArrayList<>());
        }
    }

    /** 加载使用记录（兼容旧单文件格式，自动迁移到按天存储） */
    private void loadUsageLogs() {
        File oldFile = dataDir.resolve("usage_logs.json").toFile();
        if (oldFile.exists()) {
            try {
                List<UsageLog> oldLogs = objectMapper.readValue(oldFile, new TypeReference<List<UsageLog>>() {});
                Map<String, List<UsageLog>> migrated = oldLogs.stream()
                        .collect(Collectors.groupingBy(l ->
                                l.getTimestamp() != null ? l.getTimestamp().substring(0, 10) : "unknown"));
                usageLogsByDay.putAll(migrated);
                migrated.keySet().forEach(this::saveUsageLogsByDay);
                oldFile.renameTo(dataDir.resolve("usage_logs.json.bak").toFile());
                log.info("已迁移旧 usage_logs.json 到按天存储格式");
            } catch (Exception e) {
                log.error("迁移旧 usage_logs.json 失败", e);
            }
        }
        File dataDirFile = dataDir.toFile();
        File[] dayFiles = dataDirFile.listFiles((dir, name) -> name.startsWith("usage_logs_") && name.endsWith(".json"));
        if (dayFiles != null) {
            for (File dayFile : dayFiles) {
                try {
                    String dayKey = dayFile.getName().replace("usage_logs_", "").replace(".json", "");
                    List<UsageLog> dayLogs = objectMapper.readValue(dayFile, new TypeReference<List<UsageLog>>() {});
                    usageLogsByDay.put(dayKey, Collections.synchronizedList(dayLogs));
                } catch (Exception e) {
                    log.error("加载使用日志文件 {} 失败", dayFile.getName(), e);
                }
            }
        }
    }

    /** 保存指定日期的使用记录 */
    private void saveUsageLogsByDay(String dayKey) {
        List<UsageLog> dayList = usageLogsByDay.get(dayKey);
        if (dayList == null) return;
        saveToFile("usage_logs_" + dayKey + ".json", dayList);
    }

    /** 加载 IP 注册计数 */
    private void loadIpRegisterMap() {
        File file = dataDir.resolve("ip_register.json").toFile();
        if (file.exists()) {
            try {
                ipRegisterMap = objectMapper.readValue(file,
                        new TypeReference<ConcurrentHashMap<String, Map<String, Integer>>>() {});
            } catch (Exception e) {
                log.error("加载IP注册记录失败", e);
                ipRegisterMap = new ConcurrentHashMap<>();
            }
        }
    }

    /** 保存 IP 注册计数 */
    private void saveIpRegisterMap() {
        saveToFile("ip_register.json", ipRegisterMap);
    }

    /** 通用列表加载 */
    private <T> List<T> loadList(String fileName, TypeReference<List<T>> typeRef) {
        File file = dataDir.resolve(fileName).toFile();
        if (file.exists()) {
            try {
                return Collections.synchronizedList(objectMapper.readValue(file, typeRef));
            } catch (Exception e) {
                log.error("加载文件 {} 失败", fileName, e);
            }
        }
        return Collections.synchronizedList(new ArrayList<>());
    }

    /** 通用对象保存到 JSON 文件 */
    private void saveToFile(String fileName, Object data) {
        try {
            objectMapper.writeValue(dataDir.resolve(fileName).toFile(), data);
        } catch (IOException e) {
            log.error("保存文件 {} 失败", fileName, e);
        }
    }

    // ========== 工具方法 ==========

    /**
     * SHA-256 密码哈希（加盐）
     * @param password 明文密码
     * @return 哈希后的十六进制字符串
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("chatai_salt_" + password).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    /** 获取当前时间字符串 yyyy-MM-dd HH:mm:ss */
    private String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
