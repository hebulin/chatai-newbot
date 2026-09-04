package com.chatai.newbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObservabilityService 监控口径测试：逻辑请求与重试计数、终态分类、
 * 活跃流释放不为负、首个内容耗时统计与跨服务实例持久化。
 */
class ObservabilityServiceTest {

    @TempDir
    Path tempDir;

    private ObservabilityService service;
    private ObservabilityMetricsRepository repository;

    /** 每个测试使用独立 SQLite 数据库，避免指标在用例之间串扰。 */
    @BeforeEach
    void setup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("observability.db").toAbsolutePath());
        repository = new ObservabilityMetricsRepository(new JdbcTemplate(dataSource));
        repository.initializeSchema();
        service = new ObservabilityService(repository);
    }

    /** 验证聊天终态分类与逻辑请求总数一一对应。 */
    @Test
    void chat计数_每个逻辑请求只记录一次终态() {
        // 正常完成
        service.chatStarted();
        service.chatFinished(ObservabilityService.ChatOutcome.SUCCESS);
        // 失败（含流内错误）
        service.chatStarted();
        service.chatFinished(ObservabilityService.ChatOutcome.FAILED);
        // 用户取消
        service.chatStarted();
        service.chatFinished(ObservabilityService.ChatOutcome.CANCELLED);
        // 超时
        service.chatStarted();
        service.chatFinished(ObservabilityService.ChatOutcome.TIMEOUT);

        Map<String, Object> snap = service.snapshot();
        assertEquals(4L, snap.get("chatRequestCount"), "逻辑聊天请求总数");
        assertEquals(1L, snap.get("chatSuccessCount"));
        assertEquals(1L, snap.get("chatFailureCount"));
        assertEquals(1L, snap.get("chatCancelledCount"));
        assertEquals(1L, snap.get("chatTimeoutCount"));
        assertEquals(0, snap.get("activeChats"), "全部结束后活跃流应归零");
    }

    /** 验证鉴权、限流等前置拒绝不会污染已进入上游的聊天请求数。 */
    @Test
    void 前置拒绝单独计数_不计入逻辑聊天请求() {
        service.chatRejected();
        service.chatRejected();
        Map<String, Object> snap = service.snapshot();
        assertEquals(2L, snap.get("chatRejectedCount"));
        assertEquals(0L, snap.get("chatRequestCount"), "前置拒绝不得计入逻辑聊天请求数");
    }

    /** 验证防御性终止调用不会把活跃流计数减成负数。 */
    @Test
    void 活跃流计数不为负() {
        // 未 started 直接 finished（防御路径）不得出现负数
        service.chatFinished(ObservabilityService.ChatOutcome.FAILED);
        assertEquals(0, service.snapshot().get("activeChats"));
    }

    /** 验证首内容耗时按有效样本计算平均值。 */
    @Test
    void 首内容耗时统计() {
        service.recordChatFirstToken(500);
        service.recordChatFirstToken(1500);
        Map<String, Object> snap = service.snapshot();
        assertEquals(1000L, snap.get("chatAvgTimeToFirstTokenMs"), "平均首内容耗时应为 (500+1500)/2");
    }

    /** 验证 HTTP 总量、服务端错误、慢请求和平均耗时口径。 */
    @Test
    void http请求指标_区分错误与慢请求() {
        service.recordRequest(200, 100);
        service.recordRequest(500, 100);
        service.recordRequest(200, 5000);
        Map<String, Object> snap = service.snapshot();
        assertEquals(3L, snap.get("requestCount"));
        assertEquals(1L, snap.get("serverErrorCount"));
        assertEquals(1L, snap.get("slowRequestCount"));
        assertEquals(1733L, snap.get("averageLatencyMs"), "平均耗时 (100+100+5000)/3 取整");
        assertNotNull(snap.get("metricsNote"), "快照必须携带统计口径说明");
    }

    /** 验证刷新后创建的新服务实例仍能读取此前的累计指标。 */
    @Test
    void 指标落库后_新服务实例仍可读取累计值() {
        service.recordRequest(200, 250);
        service.chatStarted();
        service.recordChatFirstToken(600);
        service.chatFinished(ObservabilityService.ChatOutcome.SUCCESS);
        service.flushPending();

        ObservabilityService restarted = new ObservabilityService(repository);
        Map<String, Object> snap = restarted.snapshot();
        assertEquals(1L, snap.get("requestCount"));
        assertEquals(1L, snap.get("chatRequestCount"));
        assertEquals(1L, snap.get("chatSuccessCount"));
        assertEquals(600L, snap.get("chatAvgTimeToFirstTokenMs"));
        assertEquals(0L, snap.get("processRequestCount"), "新进程自身计数应从零开始");
        assertFalse(restarted.history(24).isEmpty(), "持久化小时趋势必须可查询");
    }
}
