package com.chatai.newbot.config;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.StorageManager;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final StorageManager storageService;

    public AuthInterceptor(StorageManager storageService) {
        this.storageService = storageService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        // 也从cookie获取
        if (token == null || token.isEmpty()) {
            if (request.getCookies() != null) {
                for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                    if ("token".equals(cookie.getName())) {
                        token = cookie.getValue();
                        break;
                    }
                }
            }
        }

        User user = storageService.getUserByToken(token);
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
            return false;
        }

        // 被禁用账号：立即注销登录态并拒绝请求
        if (user.isDisabled()) {
            storageService.removeTokensByUserId(user.getId());
            response.setStatus(401);
            response.setHeader("X-Auth-Reason", "account_disabled");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"账号已被禁用，请联系管理员\"}");
            return false;
        }

        // IP 校验（后台可配置开关）：登录时已将 IP 绑定到 token，若当前请求 IP 与登录 IP 不一致，则要求重新登录
        if (storageService.getIpBindingEnabled()) {
            String loginIp = storageService.getTokenIp(token);
            if (loginIp != null && !loginIp.isEmpty()) {
                String currentIp = IpUtils.getClientIp(request);
                if (currentIp != null && !currentIp.isEmpty() && !loginIp.equals(currentIp)) {
                    // IP 变更：注销该 token，前端据 X-Auth-Reason 提示并跳转登录
                    storageService.removeToken(token);
                    response.setStatus(401);
                    response.setHeader("X-Auth-Reason", "ip_changed");
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"登录IP已变更，请重新登录\"}");
                    return false;
                }
            }
        }

        // 管理后台接口统一鉴权：/api/admin/** 仅限管理员访问（控制器内 checkAdmin 保留作纵深防御）
        if (request.getRequestURI().startsWith("/api/admin") && !user.isAdmin()) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"无管理员权限\"}");
            return false;
        }

        request.setAttribute("currentUser", user);
        return true;
    }
}

