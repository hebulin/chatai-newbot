package com.chatai.newbot.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 可持久化的可观测性指标，供后台诊断与部署探针使用。
 * 口径说明：
 * - requestCount/serverErrorCount/slowRequestCount/averageLatencyMs：HTTP 请求级指标（含静态资源与 API，
 *   异步请求的耗时以请求完整完成（AsyncListener.onComplete）为准，不以初始过滤器返回时间计）；
 * - chatRequestCount：用户发起的逻辑聊天请求数（进入 /chat 且通过前置校验开始流式输出才计，
 *   上游重试不重复计数）；
 * - chatSuccessCount/chatFailureCount/chatCancelledCount/chatTimeoutCount：逻辑聊天的最终结果分类，
 *   每个逻辑请求只记录一次（成功=流正常完成且无流内错误；失败=上游异常或流内错误；
 *   取消=客户端主动断开；超时=上游空闲超时；无法可靠区分用户停止与网络断开时不做细分伪造）；
 * - chatRejectedCount：鉴权/限流/配额等前置拒绝（未进入流式阶段）；
 * - chatTimeToFirstTokenMs：首个有效内容（首个 SSE chunk）耗时累计与计数；
 * - 计数器先写入内存，再按固定周期聚合到 SQLite 小时桶；服务重启后仍可查询累计值与历史趋势；
 * - activeChats/JVM 内存/uptimeSeconds 是当前进程实时值，不做跨重启累计。
 */
@Service
public class ObservabilityService {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);
    private static final int RETENTION_DAYS = 90;
    private final ObservabilityMetricsRepository repository;
    private final LongAdder requests = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder slowRequests = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final LongAdder chatRequests = new LongAdder();
    private final LongAdder chatSuccess = new LongAdder();
    private final LongAdder chatFailures = new LongAdder();
    private final LongAdder chatCancelled = new LongAdder();
    private final LongAdder chatTimeout = new LongAdder();
    private final LongAdder chatRejected = new LongAdder();
    private final LongAdder ttftTotalMs = new LongAdder();
    private final LongAdder ttftCount = new LongAdder();
    private final AtomicLong pendingRequests = new AtomicLong();
    private final AtomicLong pendingServerErrors = new AtomicLong();
    private final AtomicLong pendingSlowRequests = new AtomicLong();
    private final AtomicLong pendingTotalLatencyMs = new AtomicLong();
    private final AtomicLong pendingChatRequests = new AtomicLong();
    private final AtomicLong pendingChatSuccess = new AtomicLong();
    private final AtomicLong pendingChatFailures = new AtomicLong();
    private final AtomicLong pendingChatCancelled = new AtomicLong();
    private final AtomicLong pendingChatTimeout = new AtomicLong();
    private final AtomicLong pendingChatRejected = new AtomicLong();
    private final AtomicLong pendingTtftTotalMs = new AtomicLong();
    private final AtomicLong pendingTtftCount = new AtomicLong();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();

    /** 注入指标持久化仓储。 */
    public ObservabilityService(ObservabilityMetricsRepository repository) {
        this.repository = repository;
    }

    /** 记录一个 HTTP 请求完成情况（异步请求在 AsyncListener.onComplete 时调用） */
    public void recordRequest(int status, long latencyMs) {
        long safeLatency = Math.max(0, latencyMs);
        requests.increment();
        pendingRequests.incrementAndGet();
        totalLatencyMs.add(safeLatency);
        pendingTotalLatencyMs.addAndGet(safeLatency);
        if (status >= 500) {
            serverErrors.increment();
            pendingServerErrors.incrementAndGet();
        }
        if (latencyMs >= 3000) {
            slowRequests.increment();
            pendingSlowRequests.incrementAndGet();
        }
    }

    /** 标记聊天流开始（每个逻辑聊天请求只调用一次，上游重试不重复计数） */
    public void chatStarted() {
        chatRequests.increment();
        pendingChatRequests.incrementAndGet();
        activeChats.incrementAndGet();
    }

    /** 记录一次前置拒绝（鉴权失败/限流/配额/参数错误，未进入流式阶段） */
    public void chatRejected() {
        chatRejected.increment();
        pendingChatRejected.incrementAndGet();
    }

    /** 记录首个有效内容耗时（毫秒） */
    public void recordChatFirstToken(long latencyMs) {
        long safeLatency = Math.max(0, latencyMs);
        ttftTotalMs.add(safeLatency);
        pendingTtftTotalMs.addAndGet(safeLatency);
        ttftCount.increment();
        pendingTtftCount.incrementAndGet();
    }

    /** 聊天终态分类 */
    public enum ChatOutcome { SUCCESS, FAILED, CANCELLED, TIMEOUT }

    /**
     * 标记聊天流结束并记录最终结果分类（每个逻辑请求只记录一次）。
     * 活跃流计数在正常、异常、取消路径均正确释放，不为负。
     */
    public void chatFinished(ChatOutcome outcome) {
        activeChats.updateAndGet(value -> Math.max(0, value - 1));
        switch (outcome) {
            case SUCCESS -> {
                chatSuccess.increment();
                pendingChatSuccess.incrementAndGet();
            }
            case FAILED -> {
                chatFailures.increment();
                pendingChatFailures.incrementAndGet();
            }
            case CANCELLED -> {
                chatCancelled.increment();
                pendingChatCancelled.incrementAndGet();
            }
            case TIMEOUT -> {
                chatTimeout.increment();
                pendingChatTimeout.incrementAndGet();
            }
        }
    }

    /** 返回后台可直接展示的持久累计指标与当前进程实时指标。 */
    public synchronized Map<String, Object> snapshot() {
        ObservabilityMetricsRepository.MetricsTotals stored = repository.totals();
        ObservabilityMetricsRepository.MetricsDelta pending = pendingSnapshot();
        long requestCount = stored.requestCount() + pending.requestCount();
        long latencyTotal = stored.totalLatencyMs() + pending.totalLatencyMs();
        long ttft = stored.ttftCount() + pending.ttftCount();
        long ttftTotal = stored.ttftTotalMs() + pending.ttftTotalMs();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("metricsSince", stored.firstBucket() > 0
                ? Instant.ofEpochSecond(stored.firstBucket()).toString()
                : Instant.ofEpochMilli(startedAt).toString());
        data.put("metricsNote", "计数器按小时持久化并保留 90 天；活跃聊天流、JVM 内存和运行时长为当前进程实时值");
        data.put("requestCount", requestCount);
        data.put("serverErrorCount", stored.serverErrorCount() + pending.serverErrorCount());
        data.put("slowRequestCount", stored.slowRequestCount() + pending.slowRequestCount());
        data.put("averageLatencyMs", requestCount == 0 ? 0 : latencyTotal / requestCount);
        data.put("chatRequestCount", stored.chatRequestCount() + pending.chatRequestCount());
        // 兼容字段：chatFailureCount 保持原名（失败总数）
        data.put("chatFailureCount", stored.chatFailureCount() + pending.chatFailureCount());
        data.put("chatSuccessCount", stored.chatSuccessCount() + pending.chatSuccessCount());
        data.put("chatCancelledCount", stored.chatCancelledCount() + pending.chatCancelledCount());
        data.put("chatTimeoutCount", stored.chatTimeoutCount() + pending.chatTimeoutCount());
        data.put("chatRejectedCount", stored.chatRejectedCount() + pending.chatRejectedCount());
        data.put("activeChats", activeChats.get());
        data.put("chatAvgTimeToFirstTokenMs", ttft == 0 ? 0 : ttftTotal / ttft);
        data.put("processRequestCount", requests.sum());
        data.put("processChatRequestCount", chatRequests.sum());
        Runtime runtime = Runtime.getRuntime();
        data.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        data.put("heapMaxBytes", runtime.maxMemory());
        return data;
    }

    /** 强制把尚未落库的增量写入当前小时桶，供定时任务、测试和关闭钩子复用。 */
    @Scheduled(fixedDelayString = "${chatai.observability.flush-interval-ms:60000}")
    public synchronized void flushPending() {
        ObservabilityMetricsRepository.MetricsDelta delta = takePending();
        if (delta.isEmpty()) return;
        try {
            repository.addDelta(ObservabilityMetricsRepository.bucketStart(Instant.now().getEpochSecond()), delta);
        } catch (Exception e) {
            restorePending(delta);
            log.warn("运行指标持久化失败，增量已放回内存等待下次重试", e);
        }
    }

    /** 查询最近 1～720 小时的持久化时间序列，查询前先刷新当前内存增量。 */
    public synchronized List<Map<String, Object>> history(int hours) {
        flushPending();
        return repository.history(Math.max(1, Math.min(720, hours)));
    }

    /** 每日清理一次 90 天以前的小时桶。 */
    @Scheduled(cron = "0 20 3 * * ?")
    public void cleanupExpiredMetrics() {
        long cutoff = ObservabilityMetricsRepository.bucketStart(
                Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS).getEpochSecond());
        int deleted = repository.deleteBefore(cutoff);
        if (deleted > 0) log.info("已清理 {} 条过期运行指标小时桶", deleted);
    }

    /** 应用正常关闭前尽力刷新指标，减少最后一个刷新周期的数据损失。 */
    @PreDestroy
    public void shutdown() {
        flushPending();
    }

    /** 读取当前待持久化增量但不清空，用于生成无重复的实时累计快照。 */
    private ObservabilityMetricsRepository.MetricsDelta pendingSnapshot() {
        return new ObservabilityMetricsRepository.MetricsDelta(
                pendingRequests.get(), pendingServerErrors.get(), pendingSlowRequests.get(),
                pendingTotalLatencyMs.get(), pendingChatRequests.get(), pendingChatSuccess.get(),
                pendingChatFailures.get(), pendingChatCancelled.get(), pendingChatTimeout.get(),
                pendingChatRejected.get(), pendingTtftTotalMs.get(), pendingTtftCount.get());
    }

    /** 原子取走一个刷新批次的全部待写增量。 */
    private ObservabilityMetricsRepository.MetricsDelta takePending() {
        return new ObservabilityMetricsRepository.MetricsDelta(
                pendingRequests.getAndSet(0), pendingServerErrors.getAndSet(0),
                pendingSlowRequests.getAndSet(0), pendingTotalLatencyMs.getAndSet(0),
                pendingChatRequests.getAndSet(0), pendingChatSuccess.getAndSet(0),
                pendingChatFailures.getAndSet(0), pendingChatCancelled.getAndSet(0),
                pendingChatTimeout.getAndSet(0), pendingChatRejected.getAndSet(0),
                pendingTtftTotalMs.getAndSet(0), pendingTtftCount.getAndSet(0));
    }

    /** 数据库写入失败时把批次完整放回待写计数器，避免静默丢失指标。 */
    private void restorePending(ObservabilityMetricsRepository.MetricsDelta delta) {
        pendingRequests.addAndGet(delta.requestCount());
        pendingServerErrors.addAndGet(delta.serverErrorCount());
        pendingSlowRequests.addAndGet(delta.slowRequestCount());
        pendingTotalLatencyMs.addAndGet(delta.totalLatencyMs());
        pendingChatRequests.addAndGet(delta.chatRequestCount());
        pendingChatSuccess.addAndGet(delta.chatSuccessCount());
        pendingChatFailures.addAndGet(delta.chatFailureCount());
        pendingChatCancelled.addAndGet(delta.chatCancelledCount());
        pendingChatTimeout.addAndGet(delta.chatTimeoutCount());
        pendingChatRejected.addAndGet(delta.chatRejectedCount());
        pendingTtftTotalMs.addAndGet(delta.ttftTotalMs());
        pendingTtftCount.addAndGet(delta.ttftCount());
    }
}
