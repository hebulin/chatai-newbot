package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private final ObjectMapper objectMapper;
    private Path dataDir;

    // 内存缓存
    private List<User> users = Collections.synchronizedList(new ArrayList<>());
    private List<ModelConfig> modelConfigs = Collections.synchronizedList(new ArrayList<>());
    private List<Provider> providers = Collections.synchronizedList(new ArrayList<>());
    // 预置厂商的显示名覆盖（用户自定义，key=providerId，value=显示名），用于在不修改 providers.json 的情况下改显示名
    // 预置厂商的图标不可改，只能在自定义厂商中设置（保存在 ModelConfig.providerIcon）
    private Map<String, String> providerNameOverrides = new ConcurrentHashMap<>();
    // 按天存储的使用记录: dateKey(yyyy-MM-dd) -> 日志列表
    private Map<String, List<UsageLog>> usageLogsByDay = new ConcurrentHashMap<>();
    private Map<String, Map<String, Integer>> ipRegisterMap = new ConcurrentHashMap<>(); // date -> {ip -> count}
    private Map<String, String> activeTokens = new ConcurrentHashMap<>(); // token -> userId

    // 内置admin密码
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    public FileStorageService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }


    @PostConstruct
    public void init() {
        try {
            // 数据目录在 jar 同级的 data 文件夹中
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

            // 确保admin用户存在
            ensureAdminUser();
            log.info("已确保admin用户存在");
        } catch (Exception e) {
            log.error("初始化文件存储服务失败", e);
            throw new RuntimeException("初始化文件存储服务失败", e);
        }
        log.info("文件存储服务初始化完成");
    }

    // ========== 用户相关 ==========

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

    public User authenticate(String username, String password) {
        return users.stream()
                .filter(u -> u.getUsername().equals(username)
                        && u.getPassword().equals(hashPassword(password)))
                .findFirst()
                .orElse(null);
    }

    public boolean canRegisterFromIp(String ip) {
        String today = LocalDate.now().toString();
        Map<String, Integer> todayMap = ipRegisterMap.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        int count = todayMap.getOrDefault(ip, 0);
        return count < 5;
    }

    public User register(String username, String password, String ip) {
        // 检查用户名是否已存在
        if (users.stream().anyMatch(u -> u.getUsername().equals(username))) {
            return null;
        }
        // 不允许注册admin
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

        // 更新IP注册计数
        String today = LocalDate.now().toString();
        Map<String, Integer> todayMap = ipRegisterMap.computeIfAbsent(today, k -> new ConcurrentHashMap<>());
        todayMap.merge(ip, 1, Integer::sum);
        saveIpRegisterMap();

        return user;
    }

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

    public String createToken(String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        activeTokens.put(token, userId);
        return token;
    }

    public User getUserByToken(String token) {
        if (token == null) return null;
        String userId = activeTokens.get(token);
        if (userId == null) return null;
        return users.stream().filter(u -> u.getId().equals(userId)).findFirst().orElse(null);
    }

    public void removeToken(String token) {
        if (token != null) {
            activeTokens.remove(token);
        }
    }

    public List<User> getAllUsers() {
        return new ArrayList<>(users);
    }

    public User getUserById(String id) {
        return users.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(null);
    }

    public boolean deleteUser(String userId) {
        boolean removed = users.removeIf(u -> u.getId().equals(userId) && !"admin".equals(u.getRole()));
        if (removed) saveUsers();
        return removed;
    }

    public void updateUser(User user) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getId().equals(user.getId())) {
                users.set(i, user);
                saveUsers();
                return;
            }
        }
    }

    /**
     * 修改用户密码
     * @param userId 用户ID
     * @param oldPassword 旧密码（明文）
     * @param newPassword 新密码（明文）
     * @return 0=成功; 1=用户不存在; 2=旧密码错误
     */
    public int changePassword(String userId, String oldPassword, String newPassword) {
        User user = getUserById(userId);
        if (user == null) return 1;
        if (!user.getPassword().equals(hashPassword(oldPassword))) return 2;
        user.setPassword(hashPassword(newPassword));
        saveUsers();
        // 移除该用户的所有token，强制重新登录
        activeTokens.entrySet().removeIf(e -> userId.equals(e.getValue()));
        return 0;
    }

    // ========== 模型配置相关 ==========

    public List<ModelConfig> getAllModelConfigs() {
        return new ArrayList<>(modelConfigs);
    }

    public List<ModelConfig> getVisibleModels(User user) {
        return modelConfigs.stream()
                .filter(ModelConfig::isEnabled)
                .filter(m -> Boolean.TRUE.equals(m.getVisibleToAll())
                        || user.isAdmin()
                        || user.getAllowedModelIds().contains(m.getId()))
                .collect(Collectors.toList());
    }

    public ModelConfig getModelConfigById(String id) {
        return modelConfigs.stream().filter(m -> m.getId().equals(id)).findFirst().orElse(null);
    }

    public ModelConfig addModelConfig(ModelConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(nowString());
        }
        // 默认全员可见
        if (config.getVisibleToAll() == null) {
            config.setVisibleToAll(true);
        }
        // 自动填充厂商信息
        if (config.getProviderId() != null && !"custom".equals(config.getProviderId())) {
            providers.stream()
                    .filter(p -> p.getId().equals(config.getProviderId()))
                    .findFirst()
                    .ifPresent(p -> {
                        // 优先使用用户自定义的显示名覆盖；图标直接来自 providers.json
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
        modelConfigs.add(config);
        saveModelConfigs();
        return config;
    }

    public void updateModelConfig(ModelConfig config) {
        for (int i = 0; i < modelConfigs.size(); i++) {
            if (modelConfigs.get(i).getId().equals(config.getId())) {
                // 自动填充厂商信息（与addModelConfig保持一致）
                if (config.getProviderId() != null && !"custom".equals(config.getProviderId())) {
                    providers.stream()
                            .filter(p -> p.getId().equals(config.getProviderId()))
                            .findFirst()
                            .ifPresent(p -> {
                                // 优先使用用户自定义的显示名覆盖；图标直接来自 providers.json
                                config.setProviderName(getProviderDisplayName(p.getId()));
                                config.setProviderIcon(p.getIcon());
                                if (config.getThinkingParamType() == null || config.getThinkingParamType().isEmpty()) {
                                    config.setThinkingParamType(p.getThinkingParamType());
                                }
                            });
                }
                modelConfigs.set(i, config);
                saveModelConfigs();
                return;
            }
        }
    }

    public boolean deleteModelConfig(String id) {
        boolean removed = modelConfigs.removeIf(m -> m.getId().equals(id));
        if (removed) saveModelConfigs();
        return removed;
    }

    // ========== 厂商相关 ==========

    public List<Provider> getAllProviders() {
        // 返回时应用显示名覆盖（图标不可改，直接使用 providers.json 中的值）
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

    /**
     * 通过 ID 获取原始预置厂商（不应用覆盖）
     */
    public Provider getProvider(String providerId) {
        if (providerId == null) return null;
        return providers.stream().filter(p -> p.getId().equals(providerId)).findFirst().orElse(null);
    }

    /**
     * 获取预置厂商当前的显示名（应用了用户自定义的覆盖）
     */
    public String getProviderDisplayName(String providerId) {
        if (providerId == null) return null;
        Provider p = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
        if (p == null) return null;
        String override = providerNameOverrides.get(providerId);
        return (override != null && !override.trim().isEmpty()) ? override : p.getName();
    }

    /**
     * 修改厂商显示名/图标
     * - 预置厂商(providerId ∈ providers.json): 仅修改显示名, 图标不可改(来自 providers.json)
     * - 自定义厂商(providerId="__custom__"): 仅修改 providerName=oldName 对应的 ModelConfig（可同时改 name+icon）
     *
     * @param providerId 预置厂商ID / 自定义厂商固定 "__custom__"
     * @param newName 新的显示名（不可为空）
     * @param newIcon 新的图标（仅自定义厂商生效；预置厂商会被忽略）
     * @param oldName 自定义厂商的旧名（仅自定义厂商使用，可为 null）
     * @return 实际被改动的 ModelConfig 数量
     */
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
            // 自定义厂商：仅更新 providerId=__custom__ 且 providerName=oldName 的 ModelConfig
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
        // 预置厂商：仅允许修改显示名；图标来自 providers.json，不可改（忽略 newIcon 参数）
        Provider p = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
        if (p == null) throw new IllegalArgumentException("厂商不存在: " + providerId);
        // 名称：与默认名一致则清除覆盖
        if (trimmed.equals(p.getName())) {
            providerNameOverrides.remove(providerId);
        } else {
            providerNameOverrides.put(providerId, trimmed);
        }
        saveProviderNameOverrides();
        // 同步所有 ModelConfig（仅同步名称；图标保持 providers.json 的值不变）
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

    /**
     * 获取所有自定义厂商条目（去重，按 providerName 分组）
     * 用于在管理后台的"厂商管理"Tab 中显示
     */
    public List<Map<String, Object>> listCustomProviders() {
        // 按 providerName 分组，记录数量和最常见的图标
        Map<String, int[]> nameCount = new LinkedHashMap<>(); // name -> [count]
        Map<String, Map<String, Integer>> nameIconCount = new LinkedHashMap<>(); // name -> {icon -> count}
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
            // 选取最常见的图标
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

    // ========== 厂商显示名/图标覆盖（持久化） ==========

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

    private void saveProviderNameOverrides() {
        saveToFile("provider_names.json", providerNameOverrides);
    }

    // ========== 使用记录相关（按天存储） ==========

    public void addUsageLog(UsageLog logEntry) {
        String dayKey = logEntry.getTimestamp() != null ? logEntry.getTimestamp().substring(0, 10) : LocalDate.now().toString();
        List<UsageLog> dayList = usageLogsByDay.computeIfAbsent(dayKey, k -> Collections.synchronizedList(new ArrayList<>()));
        dayList.add(logEntry);
        saveUsageLogsByDay(dayKey);
    }

    public List<UsageLog> getAllUsageLogs() {
        List<UsageLog> all = new ArrayList<>();
        for (List<UsageLog> dayList : usageLogsByDay.values()) {
            all.addAll(dayList);
        }
        return all;
    }

    public List<UsageLog> getUsageLogsByUser(String userId) {
        return getAllUsageLogs().stream()
                .filter(l -> l.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

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

    /** 获取所有已记录的日期列表（用于查询） */
    public List<String> getUsageLogDates() {
        return usageLogsByDay.keySet().stream().sorted().collect(Collectors.toList());
    }

    // ========== 文件读写 ==========

    private void loadUsers() {
        users = loadList("users.json", new TypeReference<List<User>>() {});
    }

    private void saveUsers() {
        saveToFile("users.json", users);
    }

    private void loadModelConfigs() {
        modelConfigs = loadList("models.json", new TypeReference<List<ModelConfig>>() {});
        // 兼容旧数据：自动补全 providerName、providerIcon、thinkingParamType
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

    private void saveModelConfigs() {
        saveToFile("models.json", modelConfigs);
    }

    private void loadProviders() {
        // providers.json从classpath加载（内置的）
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

    private void loadUsageLogs() {
        // 兼容旧的单文件 usage_logs.json
        File oldFile = dataDir.resolve("usage_logs.json").toFile();
        if (oldFile.exists()) {
            try {
                List<UsageLog> oldLogs = objectMapper.readValue(oldFile, new TypeReference<List<UsageLog>>() {});
                // 迁移到按天存储
                Map<String, List<UsageLog>> migrated = oldLogs.stream()
                        .collect(Collectors.groupingBy(l ->
                                l.getTimestamp() != null ? l.getTimestamp().substring(0, 10) : "unknown"));
                usageLogsByDay.putAll(migrated);
                // 保存拆分后的文件
                migrated.keySet().forEach(this::saveUsageLogsByDay);
                // 重命名旧文件为备份
                oldFile.renameTo(dataDir.resolve("usage_logs.json.bak").toFile());
                log.info("已迁移旧 usage_logs.json 到按天存储格式");
            } catch (Exception e) {
                log.error("迁移旧 usage_logs.json 失败", e);
            }
        }
        // 加载所有 usage_logs_YYYY-MM-DD.json 文件
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

    private void saveUsageLogsByDay(String dayKey) {
        List<UsageLog> dayList = usageLogsByDay.get(dayKey);
        if (dayList == null) return;
        saveToFile("usage_logs_" + dayKey + ".json", dayList);
    }

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

    private void saveIpRegisterMap() {
        saveToFile("ip_register.json", ipRegisterMap);
    }

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

    private void saveToFile(String fileName, Object data) {
        try {
            objectMapper.writeValue(dataDir.resolve(fileName).toFile(), data);
        } catch (IOException e) {
            log.error("保存文件 {} 失败", fileName, e);
        }
    }

    // ========== 工具方法 ==========

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

    private String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
