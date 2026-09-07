package com.chatai.newbot.service;

import com.chatai.newbot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 负责内置与自定义厂商目录、模型目录覆盖和历史厂商迁移。 */
@Repository
public class ProviderCatalogRepository {
    private static final Logger log = LoggerFactory.getLogger(ProviderCatalogRepository.class);
    private final JdbcTemplate jdbcTemplate;
    private final SystemSettingsRepository settingsRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Provider> providers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> providerNameOverrides = new ConcurrentHashMap<>();

    /** 创建厂商仓储，复用设置与模型仓储的唯一缓存。 */
    public ProviderCatalogRepository(JdbcTemplate jdbcTemplate, SystemSettingsRepository settingsRepository,
                                     ModelConfigRepository modelConfigRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsRepository = settingsRepository;
        this.modelConfigRepository = modelConfigRepository;
    }

    /** 返回播种用的内置厂商配置快照。 */
    public List<Provider> getBuiltInProviders() {
        return new ArrayList<>(providers);
    }

    /** 自动填充厂商信息 */
    public void fillProviderInfo(ModelConfig config) {
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

    // ========== 厂商相关 ==========

    /** 从 classpath 加载内置厂商 providers.json */
    public void loadProviders() {
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
    public synchronized void migrateLegacyCustomProviders() {
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
            modelConfigRepository.invalidateCache();
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
        for (ModelConfig config : modelConfigRepository.findAll()) {
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

    /** 取得应用显示名和模型目录覆盖后的内置厂商。 */
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

    /** 按标识取得原始内置厂商配置。 */
    public Provider getProvider(String providerId) {
        if (providerId == null) return null;
        return providers.stream().filter(p -> p.getId().equals(providerId)).findFirst().orElse(null);
    }

    /** 取得内置厂商的覆盖显示名。 */
    public String getProviderDisplayName(String providerId) {
        if (providerId == null) return null;
        Provider p = providers.stream().filter(x -> x.getId().equals(providerId)).findFirst().orElse(null);
        if (p == null) return null;
        String override = providerNameOverrides.get(providerId);
        return (override != null && !override.trim().isEmpty()) ? override : p.getName();
    }

    /** 修改厂商名称并同步已接入模型缓存。 */
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
            modelConfigRepository.invalidateCache();
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
        List<ModelConfig> configs = modelConfigRepository.findAll();
        int updated = 0;
        for (ModelConfig c : configs) {
            if (providerId.equals(c.getProviderId()) && !trimmed.equals(c.getProviderName())) {
                c.setProviderName(trimmed);
                fillProviderInfo(c);
                modelConfigRepository.update(c);
                updated++;
            }
        }
        return updated;
    }

    /** 查询自定义厂商及其模型目录摘要。 */
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
            settingsRepository.set("provider_model_overrides", objectMapper.writeValueAsString(overrides));
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
        String json = settingsRepository.get("provider_model_overrides");
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
    public void loadProviderNameOverrides() {
        providerNameOverrides.clear();
        String json = settingsRepository.get("provider_name_overrides");
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
            settingsRepository.set("provider_name_overrides", objectMapper.writeValueAsString(providerNameOverrides));
        } catch (Exception e) {
            log.error("SQLite: 保存厂商显示名覆盖失败", e);
        }
    }


    /** 生成厂商更新时间。 */
    private String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
