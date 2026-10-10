package com.chatai.newbot.config;

import com.chatai.newbot.service.BackupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 恢复维护检查独立于登录鉴权，匿名接口也不能读取或写入正在切换的数据。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MaintenanceFilter extends OncePerRequestFilter {
    private final BackupService backups;

    /** 注入包含恢复和故障补偿状态的服务。 */
    public MaintenanceFilter(BackupService backups) {
        this.backups = backups;
    }

    /** 恢复或补偿故障期间统一拒绝 API，包括注册、登录、分享和图片。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/") && backups.isRestoring()) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"success\":false,\"message\":\"系统正在恢复数据，请稍后重试\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
