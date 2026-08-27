package com.chatai.newbot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObservabilityService 监控口径测试：逻辑请求与重试计数、终态分类、
 * 活跃流释放不为负、首个内容耗时统计、重启清零口径说明。
 */
class ObservabilityServiceTest {

    private ObservabilityService service;

    @BeforeEach
    void setup() {
        service = new ObservabilityService();
    }

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

    @Test
    void 前置拒绝单独计数_不计入逻辑聊天请求() {
        service.chatRejected();
        service.chatRejected();
        Map<String, Object> snap = service.snapshot();
        assertEquals(2L, snap.get("chatRejectedCount"));
        assertEquals(0L, snap.get("chatRequestCount"), "前置拒绝不得计入逻辑聊天请求数");
    }

    @Test
    void 活跃流计数不为负() {
        // 未 started 直接 finished（防御路径）不得出现负数
        service.chatFinished(ObservabilityService.ChatOutcome.FAILED);
        assertEquals(0, service.snapshot().get("activeChats"));
    }

    @Test
    void 首内容耗时统计() {
        service.recordChatFirstToken(500);
        service.recordChatFirstToken(1500);
        Map<String, Object> snap = service.snapshot();
        assertEquals(1000L, snap.get("chatAvgTimeToFirstTokenMs"), "平均首内容耗时应为 (500+1500)/2");
    }

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
}
