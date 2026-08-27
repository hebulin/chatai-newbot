package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 无额外依赖的进程内可观测性指标，供后台诊断与部署探针使用。
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
 * - 指标为进程内累计值，服务重启后清零，startedAt/uptimeSeconds 标明统计起点。
 */
@Service
public class ObservabilityService {
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
    private final AtomicInteger activeChats = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();

    /** 记录一个 HTTP 请求完成情况（异步请求在 AsyncListener.onComplete 时调用） */
    public void recordRequest(int status, long latencyMs) {
        requests.increment();
        totalLatencyMs.add(Math.max(0, latencyMs));
        if (status >= 500) serverErrors.increment();
        if (latencyMs >= 3000) slowRequests.increment();
    }

    /** 标记聊天流开始（每个逻辑聊天请求只调用一次，上游重试不重复计数） */
    public void chatStarted() {
        chatRequests.increment();
        activeChats.incrementAndGet();
    }

    /** 记录一次前置拒绝（鉴权失败/限流/配额/参数错误，未进入流式阶段） */
    public void chatRejected() {
        chatRejected.increment();
    }

    /** 记录首个有效内容耗时（毫秒） */
    public void recordChatFirstToken(long latencyMs) {
        ttftTotalMs.add(Math.max(0, latencyMs));
        ttftCount.increment();
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
            case SUCCESS -> chatSuccess.increment();
            case FAILED -> chatFailures.increment();
            case CANCELLED -> chatCancelled.increment();
            case TIMEOUT -> chatTimeout.increment();
        }
    }

    /** 返回后台可直接展示的当前指标快照（含统计口径起点与分类明细） */
    public Map<String, Object> snapshot() {
        long requestCount = requests.sum();
        long ttft = ttftCount.sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("metricsNote", "进程内累计指标，服务重启后清零；聊天指标按逻辑请求统计（上游重试不重复计数）");
        data.put("requestCount", requestCount);
        data.put("serverErrorCount", serverErrors.sum());
        data.put("slowRequestCount", slowRequests.sum());
        data.put("averageLatencyMs", requestCount == 0 ? 0 : totalLatencyMs.sum() / requestCount);
        data.put("chatRequestCount", chatRequests.sum());
        // 兼容字段：chatFailureCount 保持原名（失败总数）
        data.put("chatFailureCount", chatFailures.sum());
        data.put("chatSuccessCount", chatSuccess.sum());
        data.put("chatCancelledCount", chatCancelled.sum());
        data.put("chatTimeoutCount", chatTimeout.sum());
        data.put("chatRejectedCount", chatRejected.sum());
        data.put("activeChats", activeChats.get());
        data.put("chatAvgTimeToFirstTokenMs", ttft == 0 ? 0 : ttftTotalMs.sum() / ttft);
        Runtime runtime = Runtime.getRuntime();
        data.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        data.put("heapMaxBytes", runtime.maxMemory());
        return data;
    }
}
