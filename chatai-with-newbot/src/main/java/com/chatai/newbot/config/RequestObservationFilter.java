package com.chatai.newbot.config;

import com.chatai.newbot.service.ObservabilityService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求补充 requestId、响应头与基础耗时/错误指标。
 * Servlet 异步请求（SSE 流式响应）：初始过滤器返回时流仍在异步写出，
 * 耗时以 AsyncListener.onComplete（异步真正完成）为准，不以初始返回时间计；
 * MDC 的 requestId 在异步线程中由异步上下文传播，完成后统一清理。
 */
@Component
public class RequestObservationFilter extends OncePerRequestFilter {
    private final ObservabilityService observabilityService;

    /** 注入进程内指标收集服务。 */
    public RequestObservationFilter(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    /** 记录一次请求；请求 ID 只接受有限字符，避免污染日志上下文。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9._-]{8,80}")
                ? supplied : UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // MDC 在请求线程清理（异步回调线程的 MDC 与请求线程无关，不能交叉清理）
            MDC.remove("requestId");
            if (request.isAsyncStarted()) {
                // 异步请求（SSE 流式）：注册完成监听，完整耗时在异步结束时记录
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                        observabilityService.recordRequest(response.getStatus(), latencyMs);
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        // 异步超时按当前状态码记录，耗时算到超时点
                        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                        observabilityService.recordRequest(response.getStatus(), latencyMs);
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        // 异步错误：记录 5xx 口径（状态码可能仍为 200，按错误事件修正）
                        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                        int status = response.getStatus() >= 500 ? response.getStatus() : 500;
                        observabilityService.recordRequest(status, latencyMs);
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        // 异步重分发：无需重复注册
                    }
                });
            } else {
                long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                observabilityService.recordRequest(response.getStatus(), latencyMs);
            }
        }
    }
}
