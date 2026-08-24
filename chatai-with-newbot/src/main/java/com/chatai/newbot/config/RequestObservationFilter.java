package com.chatai.newbot.config;

import com.chatai.newbot.service.ObservabilityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 为每个请求补充 requestId、响应头与基础耗时/错误指标。 */
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
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            observabilityService.recordRequest(response.getStatus(), latencyMs);
            MDC.remove("requestId");
        }
    }
}
