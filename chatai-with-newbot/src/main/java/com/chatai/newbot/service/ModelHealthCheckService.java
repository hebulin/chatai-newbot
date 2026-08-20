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
 * 模型健康检查定时任务：按固定间隔对所有启用模型执行连通测试。
 *
 * 复用 {@link UnifiedChatService#testConnection}（最小请求测连通 + 短生成测速），
 * 测试指标（延迟/速度/时间）由 testConnection 持久化到模型配置，
 * 管理端模型列表直接展示最近一次测试结果，失败模型历史指标被清空并更新测试时间。
 *
 * 开关与间隔由 application.yml 的 chatai.health-check.* 控制
 * （可用环境变量 CHATAI_HEALTHCHECK_ENABLED / CHATAI_HEALTHCHECK_INTERVAL_MINUTES 覆盖），
 * 每次执行前实时读取开关，便于不改配置重启式调整。
 */
@Service
public class ModelHealthCheckService {
    private static final Logger log = LoggerFactory.getLogger(ModelHealthCheckService.class);

    /** 相邻模型测试间隔，避免对同一厂商并发突发请求 */
    private static final long BETWEEN_MODELS_MS = 2000;

    private final UnifiedChatService unifiedChatService;
    private final StorageManager storageManager;

    @Value("${chatai.health-check.enabled:true}")
    private boolean enabled;

    public ModelHealthCheckService(UnifiedChatService unifiedChatService, StorageManager storageManager) {
        this.unifiedChatService = unifiedChatService;
        this.storageManager = storageManager;
    }

    /**
     * 按配置间隔执行一轮健康检查：逐个测试启用模型并汇总成功/失败数量。
     * 串行执行 + 模型间小延时，避免突发并发请求；单个模型失败不影响其余模型。
     */
    @Scheduled(fixedDelayString = "${chatai.health-check.interval-minutes:360}",
            initialDelayString = "${chatai.health-check.initial-delay-minutes:5}",
            timeUnit = TimeUnit.MINUTES)
    public void runHealthCheck() {
        if (!enabled) {
            return;
        }
        List<ModelConfig> models = storageManager.getAllModelConfigs().stream()
                .filter(ModelConfig::isEnabled)
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
        log.info("模型健康检查完成: 共{}个启用模型, 成功{}, 失败{}", models.size(), ok, fail);
    }
}
