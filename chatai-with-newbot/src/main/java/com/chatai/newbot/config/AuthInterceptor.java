package com.chatai.newbot.config;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.FileStorageService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final FileStorageService storageService;

    public AuthInterceptor(FileStorageService storageService) {
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

        // IP 校验：登录时已将 IP 绑定到 token，若当前请求 IP 与登录 IP 不一致，则要求重新登录
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

        request.setAttribute("currentUser", user);
        return true;
    }
}

