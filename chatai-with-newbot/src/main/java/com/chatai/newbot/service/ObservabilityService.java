package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** 无额外依赖的进程内可观测性指标，供后台诊断与部署探针使用。 */
@Service
public class ObservabilityService {
    private final LongAdder requests = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder slowRequests = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final LongAdder chatRequests = new LongAdder();
    private final LongAdder chatFailures = new LongAdder();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final long startedAt = System.currentTimeMillis();

    /** 记录一个 HTTP 请求完成情况。 */
    public void recordRequest(int status, long latencyMs) {
        requests.increment();
        totalLatencyMs.add(Math.max(0, latencyMs));
        if (status >= 500) serverErrors.increment();
        if (latencyMs >= 3000) slowRequests.increment();
    }

    /** 标记聊天流开始。 */
    public void chatStarted() {
        chatRequests.increment();
        activeChats.incrementAndGet();
    }

    /** 标记聊天流结束并按信号记录失败。 */
    public void chatFinished(boolean failed) {
        activeChats.updateAndGet(value -> Math.max(0, value - 1));
        if (failed) chatFailures.increment();
    }

    /** 返回后台可直接展示的当前指标快照。 */
    public Map<String, Object> snapshot() {
        long requestCount = requests.sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
        data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        data.put("requestCount", requestCount);
        data.put("serverErrorCount", serverErrors.sum());
        data.put("slowRequestCount", slowRequests.sum());
        data.put("averageLatencyMs", requestCount == 0 ? 0 : totalLatencyMs.sum() / requestCount);
        data.put("chatRequestCount", chatRequests.sum());
        data.put("chatFailureCount", chatFailures.sum());
        data.put("activeChats", activeChats.get());
        Runtime runtime = Runtime.getRuntime();
        data.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        data.put("heapMaxBytes", runtime.maxMemory());
        return data;
    }
}
