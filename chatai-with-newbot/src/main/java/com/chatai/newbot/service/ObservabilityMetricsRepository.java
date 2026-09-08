package com.chatai.newbot.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行指标 SQLite 仓储：按小时保存计数器增量，避免服务重启后丢失历史统计。
 */
@Repository
public class ObservabilityMetricsRepository {
    private static final long HOUR_SECONDS = 3600L;
    private final JdbcTemplate jdbcTemplate;

    /** 注入项目统一使用的 SQLite JdbcTemplate。 */
    public ObservabilityMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 创建按小时聚合表；该操作幂等，可兼容既有数据库直接升级。 */
    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_observability_hourly (" +
                "bucket_start INTEGER PRIMARY KEY," +
                "request_count INTEGER NOT NULL DEFAULT 0," +
                "server_error_count INTEGER NOT NULL DEFAULT 0," +
                "slow_request_count INTEGER NOT NULL DEFAULT 0," +
                "total_latency_ms INTEGER NOT NULL DEFAULT 0," +
                "chat_request_count INTEGER NOT NULL DEFAULT 0," +
                "chat_success_count INTEGER NOT NULL DEFAULT 0," +
                "chat_failure_count INTEGER NOT NULL DEFAULT 0," +
                "chat_cancelled_count INTEGER NOT NULL DEFAULT 0," +
                "chat_timeout_count INTEGER NOT NULL DEFAULT 0," +
                "chat_rejected_count INTEGER NOT NULL DEFAULT 0," +
                "ttft_total_ms INTEGER NOT NULL DEFAULT 0," +
                "ttft_count INTEGER NOT NULL DEFAULT 0," +
                "updated_at TEXT NOT NULL" +
                ")");
    }

    /**
     * 将一个刷新周期的指标增量原子合并到所在小时桶。
     * SQLite 的 ON CONFLICT 更新保证并发刷新不会覆盖已经落库的数据。
     */
    public void addDelta(long bucketStartEpochSeconds, MetricsDelta delta) {
        if (delta == null || delta.isEmpty()) return;
        jdbcTemplate.update("""
                INSERT INTO t_observability_hourly (
                    bucket_start, request_count, server_error_count, slow_request_count, total_latency_ms,
                    chat_request_count, chat_success_count, chat_failure_count, chat_cancelled_count,
                    chat_timeout_count, chat_rejected_count, ttft_total_ms, ttft_count, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(bucket_start) DO UPDATE SET
                    request_count = request_count + excluded.request_count,
                    server_error_count = server_error_count + excluded.server_error_count,
                    slow_request_count = slow_request_count + excluded.slow_request_count,
                    total_latency_ms = total_latency_ms + excluded.total_latency_ms,
                    chat_request_count = chat_request_count + excluded.chat_request_count,
                    chat_success_count = chat_success_count + excluded.chat_success_count,
                    chat_failure_count = chat_failure_count + excluded.chat_failure_count,
                    chat_cancelled_count = chat_cancelled_count + excluded.chat_cancelled_count,
                    chat_timeout_count = chat_timeout_count + excluded.chat_timeout_count,
                    chat_rejected_count = chat_rejected_count + excluded.chat_rejected_count,
                    ttft_total_ms = ttft_total_ms + excluded.ttft_total_ms,
                    ttft_count = ttft_count + excluded.ttft_count,
                    updated_at = excluded.updated_at
                """,
                bucketStartEpochSeconds, delta.requestCount(), delta.serverErrorCount(),
                delta.slowRequestCount(), delta.totalLatencyMs(), delta.chatRequestCount(),
                delta.chatSuccessCount(), delta.chatFailureCount(), delta.chatCancelledCount(),
                delta.chatTimeoutCount(), delta.chatRejectedCount(), delta.ttftTotalMs(),
                delta.ttftCount(), Instant.now().toString());
    }

    /** 查询全部已持久化小时桶的累计值。 */
    public MetricsTotals totals() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT MIN(bucket_start) AS first_bucket,
                       COALESCE(SUM(request_count), 0) AS request_count,
                       COALESCE(SUM(server_error_count), 0) AS server_error_count,
                       COALESCE(SUM(slow_request_count), 0) AS slow_request_count,
                       COALESCE(SUM(total_latency_ms), 0) AS total_latency_ms,
                       COALESCE(SUM(chat_request_count), 0) AS chat_request_count,
                       COALESCE(SUM(chat_success_count), 0) AS chat_success_count,
                       COALESCE(SUM(chat_failure_count), 0) AS chat_failure_count,
                       COALESCE(SUM(chat_cancelled_count), 0) AS chat_cancelled_count,
                       COALESCE(SUM(chat_timeout_count), 0) AS chat_timeout_count,
                       COALESCE(SUM(chat_rejected_count), 0) AS chat_rejected_count,
                       COALESCE(SUM(ttft_total_ms), 0) AS ttft_total_ms,
                       COALESCE(SUM(ttft_count), 0) AS ttft_count
                FROM t_observability_hourly
                """);
        return new MetricsTotals(
                number(row.get("first_bucket")), number(row.get("request_count")),
                number(row.get("server_error_count")), number(row.get("slow_request_count")),
                number(row.get("total_latency_ms")), number(row.get("chat_request_count")),
                number(row.get("chat_success_count")), number(row.get("chat_failure_count")),
                number(row.get("chat_cancelled_count")), number(row.get("chat_timeout_count")),
                number(row.get("chat_rejected_count")), number(row.get("ttft_total_ms")),
                number(row.get("ttft_count")));
    }

    /** 查询最近若干小时的稀疏时间序列，未产生流量的小时不返回空行。 */
    public List<Map<String, Object>> history(int hours) {
        long currentBucket = bucketStart(Instant.now().getEpochSecond());
        long from = currentBucket - (Math.max(1, hours) - 1L) * HOUR_SECONDS;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT bucket_start, request_count, server_error_count, slow_request_count,
                       total_latency_ms, chat_request_count, chat_success_count, chat_failure_count,
                       chat_cancelled_count, chat_timeout_count, chat_rejected_count,
                       ttft_total_ms, ttft_count
                FROM t_observability_hourly
                WHERE bucket_start >= ?
                ORDER BY bucket_start ASC
                """, from);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long requestCount = number(row.get("request_count"));
            long ttftCount = number(row.get("ttft_count"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucketStart", Instant.ofEpochSecond(number(row.get("bucket_start"))).toString());
            item.put("requestCount", requestCount);
            item.put("serverErrorCount", number(row.get("server_error_count")));
            item.put("slowRequestCount", number(row.get("slow_request_count")));
            item.put("averageLatencyMs", requestCount == 0 ? 0 : number(row.get("total_latency_ms")) / requestCount);
            item.put("chatRequestCount", number(row.get("chat_request_count")));
            item.put("chatSuccessCount", number(row.get("chat_success_count")));
            item.put("chatFailureCount", number(row.get("chat_failure_count")));
            item.put("chatCancelledCount", number(row.get("chat_cancelled_count")));
            item.put("chatTimeoutCount", number(row.get("chat_timeout_count")));
            item.put("chatRejectedCount", number(row.get("chat_rejected_count")));
            item.put("chatAvgTimeToFirstTokenMs", ttftCount == 0 ? 0 : number(row.get("ttft_total_ms")) / ttftCount);
            result.add(item);
        }
        return result;
    }

    /** 删除保留期之外的小时桶，控制长期运行时的数据表体积。 */
    public int deleteBefore(long epochSeconds) {
        return jdbcTemplate.update("DELETE FROM t_observability_hourly WHERE bucket_start < ?", epochSeconds);
    }

    /** 将任意秒级时间戳归一到 UTC 小时起点。 */
    public static long bucketStart(long epochSeconds) {
        return Math.floorDiv(epochSeconds, HOUR_SECONDS) * HOUR_SECONDS;
    }

    /** 将 JDBC 数字或空值安全转换为 long。 */
    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 单次刷新需要持久化的指标增量。 */
    public record MetricsDelta(long requestCount, long serverErrorCount, long slowRequestCount,
                               long totalLatencyMs, long chatRequestCount, long chatSuccessCount,
                               long chatFailureCount, long chatCancelledCount, long chatTimeoutCount,
                               long chatRejectedCount, long ttftTotalMs, long ttftCount) {
        /** 判断本批次是否完全没有可写入的数据。 */
        public boolean isEmpty() {
            return requestCount == 0 && serverErrorCount == 0 && slowRequestCount == 0
                    && totalLatencyMs == 0 && chatRequestCount == 0 && chatSuccessCount == 0
                    && chatFailureCount == 0 && chatCancelledCount == 0 && chatTimeoutCount == 0
                    && chatRejectedCount == 0 && ttftTotalMs == 0 && ttftCount == 0;
        }
    }

    /** 数据库中全部小时桶的累计统计。 */
    public record MetricsTotals(long firstBucket, long requestCount, long serverErrorCount,
                                long slowRequestCount, long totalLatencyMs, long chatRequestCount,
                                long chatSuccessCount, long chatFailureCount, long chatCancelledCount,
                                long chatTimeoutCount, long chatRejectedCount, long ttftTotalMs,
                                long ttftCount) {
    }
}
