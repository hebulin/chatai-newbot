package com.chatai.newbot.config;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.FileStorageService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
                for (javax.servlet.http.Cookie cookie : request.getCookies()) {
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

        request.setAttribute("currentUser", user);
        return true;
    }
}
