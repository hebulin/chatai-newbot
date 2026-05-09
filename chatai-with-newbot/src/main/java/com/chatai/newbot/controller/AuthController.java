package com.chatai.newbot.controller;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final FileStorageService storageService;

    public AuthController(FileStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "用户名和密码不能为空");
            return result;
        }

        User user = storageService.authenticate(username.trim(), password);
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        String ip = getClientIp(request);
        String browser = getClientBrowser(request);
        storageService.updateLoginInfo(user.getId(), ip, browser);
        String token = storageService.createToken(user.getId());

        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String username = body.get("username");
        String password = body.get("password");
        String ip = getClientIp(request);

        if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "用户名和密码不能为空");
            return result;
        }

        if (username.trim().length() < 2 || username.trim().length() > 20) {
            result.put("success", false);
            result.put("message", "用户名长度需在2-20个字符之间");
            return result;
        }

        if (password.length() < 4) {
            result.put("success", false);
            result.put("message", "密码长度不能少于4个字符");
            return result;
        }

        if ("admin".equalsIgnoreCase(username.trim())) {
            result.put("success", false);
            result.put("message", "该用户名不可注册");
            return result;
        }

        if (!storageService.canRegisterFromIp(ip)) {
            result.put("success", false);
            result.put("message", "该IP地址今日注册次数已达上限（每日5个）");
            return result;
        }

        User user = storageService.register(username.trim(), password, ip);
        if (user == null) {
            result.put("success", false);
            result.put("message", "用户名已存在");
            return result;
        }

        String token = storageService.createToken(user.getId());
        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        storageService.removeToken(token);
        result.put("success", true);
        return result;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        if (user != null) {
            result.put("success", true);
            result.put("username", user.getUsername());
            result.put("role", user.getRole());
            result.put("id", user.getId());
        } else {
            result.put("success", false);
        }
        return result;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private String getClientBrowser(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isEmpty()) return "未知";
        // 解析常见浏览器
        if (ua.contains("Edg/")) return "Edge";
        if (ua.contains("Chrome/") && !ua.contains("Edg/")) return "Chrome";
        if (ua.contains("Firefox/")) return "Firefox";
        if (ua.contains("Safari/") && !ua.contains("Chrome/")) return "Safari";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        return "其他";
    }
}
