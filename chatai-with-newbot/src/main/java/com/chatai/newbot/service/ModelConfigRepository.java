package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** SQLite 模型配置仓储，包含列表失效式缓存与 API Key 加解密。 */
@Repository
public class ModelConfigRepository {
    private final JdbcTemplate jdbcTemplate;
    private volatile List<ModelConfig> cache;
    private final RowMapper<ModelConfig> rowMapper = (resultSet, rowNumber) -> {
        ModelConfig model = new ModelConfig();
        model.setId(resultSet.getString("id"));
        model.setProviderId(resultSet.getString("provider_id"));
        model.setProviderName(resultSet.getString("provider_name"));
        model.setProviderIcon(resultSet.getString("provider_icon"));
        model.setModelId(resultSet.getString("model_id"));
        model.setDisplayName(resultSet.getString("display_name"));
        model.setApiKey(ApiKeyCrypto.decrypt(resultSet.getString("api_key")));
        model.setApiUrl(resultSet.getString("api_url"));
        model.setProtocol(resultSet.getString("protocol"));
        model.setThinkingParamType(resultSet.getString("thinking_param_type"));
        model.setSupportsThinking(resultSet.getInt("supports_thinking") == 1);
        model.setSupportsMultimodal(resultSet.getInt("supports_multimodal") == 1);
        model.setEnabled(resultSet.getInt("enabled") == 1);
        int visibleToAll = resultSet.getInt("visible_to_all");
        model.setVisibleToAll(resultSet.wasNull() || visibleToAll == 1);
        int healthCheck = resultSet.getInt("health_check_enabled");
        model.setHealthCheckEnabled(resultSet.wasNull() || healthCheck == 1);
        model.setBuiltIn(resultSet.getInt("built_in") == 1);
        model.setCreatedAt(resultSet.getString("created_at"));
        int latency = resultSet.getInt("test_latency_ms");
        model.setTestLatencyMs(resultSet.wasNull() ? null : latency);
        double speed = resultSet.getDouble("test_speed");
        model.setTestSpeed(resultSet.wasNull() ? null : speed);
        model.setTestedAt(resultSet.getString("tested_at"));
        model.setInputPriceCny(resultSet.getDouble("input_price_cny"));
        model.setOutputPriceCny(resultSet.getDouble("output_price_cny"));
        model.setCachedPriceCny(resultSet.getDouble("cached_price_cny"));
        model.setReasoningPriceCny(resultSet.getDouble("reasoning_price_cny"));
        int contextWindow = resultSet.getInt("context_window");
        model.setContextWindow(resultSet.wasNull() || contextWindow <= 0 ? null : contextWindow);
        return model;
    };

    /** 创建模型配置仓储。 */
    public ModelConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询全部模型配置并复用失效式缓存。 */
    public List<ModelConfig> findAll() {
        List<ModelConfig> cached = cache;
        if (cached == null) {
            cached = jdbcTemplate.query("SELECT * FROM t_model_config", rowMapper);
            cache = cached;
        }
        return new ArrayList<>(cached);
    }

    /** 插入模型配置并使缓存失效。 */
    public void insert(ModelConfig model) {
        jdbcTemplate.update(
                "INSERT INTO t_model_config (id, provider_id, provider_name, provider_icon, model_id, display_name, api_key, api_url, protocol, thinking_param_type, supports_thinking, supports_multimodal, enabled, visible_to_all, health_check_enabled, built_in, created_at, test_latency_ms, test_speed, tested_at, input_price_cny, output_price_cny, cached_price_cny, reasoning_price_cny, context_window) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                values(model));
        invalidateCache();
    }

    /** 更新模型配置并使缓存失效。 */
    public void update(ModelConfig model) {
        Object[] values = values(model);
        Object[] updateValues = new Object[values.length];
        System.arraycopy(values, 1, updateValues, 0, values.length - 1);
        updateValues[updateValues.length - 1] = model.getId();
        jdbcTemplate.update(
                "UPDATE t_model_config SET provider_id=?, provider_name=?, provider_icon=?, model_id=?, display_name=?, api_key=?, api_url=?, protocol=?, thinking_param_type=?, supports_thinking=?, supports_multimodal=?, enabled=?, visible_to_all=?, health_check_enabled=?, built_in=?, created_at=?, test_latency_ms=?, test_speed=?, tested_at=?, input_price_cny=?, output_price_cny=?, cached_price_cny=?, reasoning_price_cny=?, context_window=? WHERE id=?",
                updateValues);
        invalidateCache();
    }

    /** 删除模型配置并返回是否命中记录。 */
    public boolean delete(String id) {
        boolean deleted = jdbcTemplate.update("DELETE FROM t_model_config WHERE id = ?", id) > 0;
        invalidateCache();
        return deleted;
    }

    /** 使模型配置缓存失效。 */
    public void invalidateCache() {
        cache = null;
    }

    /** 将模型配置转换为插入语句参数。 */
    private Object[] values(ModelConfig model) {
        return new Object[] {
                model.getId(), model.getProviderId(), model.getProviderName(), model.getProviderIcon(),
                model.getModelId(), model.getDisplayName(), ApiKeyCrypto.encrypt(model.getApiKey()), model.getApiUrl(),
                model.getProtocol(), model.getThinkingParamType(), model.isSupportsThinking() ? 1 : 0,
                model.isSupportsMultimodal() ? 1 : 0, model.isEnabled() ? 1 : 0,
                model.getVisibleToAll() == null || model.getVisibleToAll() ? 1 : 0,
                model.getHealthCheckEnabled() == null || model.getHealthCheckEnabled() ? 1 : 0,
                model.isBuiltIn() ? 1 : 0, model.getCreatedAt(), model.getTestLatencyMs(), model.getTestSpeed(),
                model.getTestedAt(), nonNegative(model.getInputPriceCny()), nonNegative(model.getOutputPriceCny()),
                nonNegative(model.getCachedPriceCny()), nonNegative(model.getReasoningPriceCny()),
                model.getContextWindow() == null ? 0 : Math.max(0, model.getContextWindow())
        };
    }

    /** 将非法或负数价格归零。 */
    private double nonNegative(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0D;
    }
}
