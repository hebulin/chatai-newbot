package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 模型健康检查定时任务：对启用且开启健康检查开关的模型执行连通测试。
 *
 * 复用 {@link UnifiedChatService#testConnection}（最小请求测连通 + 短生成测速），
 * 测试指标（延迟/速度/时间）由 testConnection 持久化到模型配置，
 * 管理端模型列表直接展示最近一次测试结果，失败模型历史指标被清空并更新测试时间。
 *
 * 配置来源（运行值 = 后台"系统设置-模型健康检查"优先，未设置时回退 yml 默认）：
 * - 总开关：t_setting.health_check_enabled / application.yml chatai.health-check.enabled
 * - 检查间隔：t_setting.health_check_interval_minutes / chatai.health-check.interval-minutes
 * - 首次执行延迟：仅 yml chatai.health-check.initial-delay-minutes
 * - 单个模型是否参与：ModelConfig.healthCheckEnabled（后台模型开关，默认参与）
 *
 * 调度采用"固定 1 分钟心跳 + 到期判定"而非直接把间隔传给 @Scheduled，
 * 使后台修改开关/间隔后无需重启即可生效（@Scheduled 的周期在启动时即固定）。
 */
@Service
public class ModelHealthCheckService {
    private static final Logger log = LoggerFactory.getLogger(ModelHealthCheckService.class);

    /** 相邻模型测试间隔，避免对同一厂商并发突发请求 */
    private static final long BETWEEN_MODELS_MS = 2000;
    /** 心跳间隔：固定 1 分钟触发一次，是否真正执行由到期判定决定 */
    private static final long TICK_MS = 60_000L;

    /** 后台设置键：总开关（"true"/"false"，缺省回退 yml） */
    static final String SETTING_ENABLED = "health_check_enabled";
    /** 后台设置键：检查间隔分钟数（缺省/非法回退 yml） */
    static final String SETTING_INTERVAL_MINUTES = "health_check_interval_minutes";

    private final UnifiedChatService unifiedChatService;
    private final StorageManager storageManager;

    @Value("${chatai.health-check.enabled:true}")
    private boolean enabledDefault;
    @Value("${chatai.health-check.interval-minutes:360}")
    private long intervalMinutesDefault;
    @Value("${chatai.health-check.initial-delay-minutes:5}")
    private long initialDelayMinutesDefault;

    /** 进程启动时间戳：首次执行需满足启动后 initial-delay 的宽限 */
    private final long startedAt = System.currentTimeMillis();
    /** 最近一次实际执行的时间戳，0 表示启动后尚未执行过 */
    private volatile long lastRunAt = 0L;

    public ModelHealthCheckService(UnifiedChatService unifiedChatService, StorageManager storageManager) {
        this.unifiedChatService = unifiedChatService;
        this.storageManager = storageManager;
    }

    /**
     * 生效的总开关：后台设置（t_setting）优先，未设置时用 yml 默认值。
     *
     * @return true 表示定时健康检查开启
     */
    public boolean isEnabledEffective() {
        String v = storageManager.getSetting(SETTING_ENABLED);
        return v == null ? enabledDefault : "true".equals(v);
    }

    /**
     * 生效的检查间隔（分钟）：后台设置优先，非法或未设置时用 yml 默认值，下限 1 分钟。
     *
     * @return 检查间隔分钟数（>=1）
     */
    public long getIntervalMinutesEffective() {
        String v = storageManager.getSetting(SETTING_INTERVAL_MINUTES);
        if (v != null) {
            try {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignore) {
                // 非法值回退 yml 默认
            }
        }
        return Math.max(1, intervalMinutesDefault);
    }

    /**
     * 调度心跳：每分钟触发一次，按生效开关与间隔判定是否到期执行一轮健康检查。
     * 心跳与检查周期解耦，后台修改开关/间隔后下一个心跳即按新配置生效。
     */
    @Scheduled(fixedDelay = TICK_MS, initialDelay = TICK_MS, timeUnit = TimeUnit.MILLISECONDS)
    public void tick() {
        if (!isEnabledEffective()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastRunAt > 0) {
            // 已执行过：距上次不足生效间隔则跳过
            if (now - lastRunAt < getIntervalMinutesEffective() * 60_000L) {
                return;
            }
        } else if (now - startedAt < Math.max(0, initialDelayMinutesDefault) * 60_000L) {
            // 首次执行：需满足启动后 initial-delay 宽限
            return;
        }
        lastRunAt = now;
        runHealthCheck();
    }

    /**
     * 执行一轮健康检查：逐个测试启用且开启健康检查开关的模型并汇总成功/失败数量。
     * 串行执行 + 模型间小延时，避免突发并发请求；单个模型失败不影响其余模型。
     */
    private void runHealthCheck() {
        List<ModelConfig> models = storageManager.getAllModelConfigs().stream()
                .filter(ModelConfig::isEnabled)
                // 仅检查后台管理中被开启"健康检查"开关的模型（NULL 视为开启）
                .filter(m -> !Boolean.FALSE.equals(m.getHealthCheckEnabled()))
                .filter(m -> m.getApiUrl() != null && !m.getApiUrl().trim().isEmpty())
                .filter(m -> m.getApiKey() != null && !m.getApiKey().trim().isEmpty())
                .toList();
        if (models.isEmpty()) {
            return;
        }
        int ok = 0;
        int fail = 0;
        for (ModelConfig m : models) {
            try {
                Map<String, Object> res = unifiedChatService.testConnection(m.getId());
                if (Boolean.TRUE.equals(res.get("success"))) ok++;
                else fail++;
            } catch (Exception e) {
                fail++;
                log.warn("健康检查异常: {} ({}) -> {}", m.getDisplayName(), m.getModelId(), e.getMessage());
            }
            // 模型间小延时，最后一个模型后不再等待
            if (ok + fail < models.size()) {
                try {
                    Thread.sleep(BETWEEN_MODELS_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.info("模型健康检查完成: 共{}个待检模型, 成功{}, 失败{}", models.size(), ok, fail);
    }
}
