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
    private List<UsageLog> usageLogs = Collections.synchronizedList(new ArrayList<>());
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
            loadUsers();
            log.info("已加载 {} 个用户", users.size());
            loadModelConfigs();
            log.info("已加载 {} 个模型配置", modelConfigs.size());
            loadUsageLogs();
            log.info("已加载 {} 个使用日志", usageLogs.size());
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
                        if (config.getProviderName() == null || config.getProviderName().isEmpty()) {
                            config.setProviderName(p.getName());
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
                                config.setProviderName(p.getName());
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
        return new ArrayList<>(providers);
    }

    // ========== 使用记录相关 ==========

    public void addUsageLog(UsageLog log) {
        usageLogs.add(log);
        // 只保留最近10000条
        if (usageLogs.size() > 10000) {
            usageLogs = Collections.synchronizedList(
                    new ArrayList<>(usageLogs.subList(usageLogs.size() - 10000, usageLogs.size())));
        }
        saveUsageLogs();
    }

    public List<UsageLog> getUsageLogs() {
        return new ArrayList<>(usageLogs);
    }

    public List<UsageLog> getUsageLogsByUser(String userId) {
        return usageLogs.stream()
                .filter(l -> l.getUserId().equals(userId))
                .collect(Collectors.toList());
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
        usageLogs = loadList("usage_logs.json", new TypeReference<List<UsageLog>>() {});
    }

    private void saveUsageLogs() {
        saveToFile("usage_logs.json", usageLogs);
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
