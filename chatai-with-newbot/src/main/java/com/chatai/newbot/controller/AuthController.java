package com.chatai.newbot.controller;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.LoginAttemptService;
import com.chatai.newbot.service.StorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final StorageManager storageService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(StorageManager storageService, LoginAttemptService loginAttemptService) {
        this.storageService = storageService;
        this.loginAttemptService = loginAttemptService;
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

        String ip = getClientIp(request);

        // 防爆破：检查账户/IP 是否因连续失败被锁定
        long lockRemain = loginAttemptService.getLockRemainSeconds(username.trim(), ip);
        if (lockRemain > 0) {
            result.put("success", false);
            result.put("message", "登录失败次数过多，请 " + ((lockRemain + 59) / 60) + " 分钟后重试");
            return result;
        }

        User user = storageService.authenticate(username.trim(), password);
        if (user == null) {
            loginAttemptService.onFailure(username.trim(), ip);
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }
        // 被禁用账号拒绝登录（不计入爆破失败次数，避免误锁）
        if (user.isDisabled()) {
            result.put("success", false);
            result.put("message", "账号已被禁用，请联系管理员");
            return result;
        }
        loginAttemptService.onSuccess(username.trim(), ip);

        String browser = getClientBrowser(request);
        storageService.updateLoginInfo(user.getId(), ip, browser);
        String token = storageService.createToken(user.getId(), ip, browser);

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

        if (!isPasswordStrong(password)) {
            result.put("success", false);
            result.put("message", "密码长度至少 8 位，且需同时包含字母和数字");
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

        String token = storageService.createToken(user.getId(), ip, getClientBrowser(request));
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

    /**
     * 登录设备管理：查询当前账号的所有登录会话（登录时间/浏览器/IP，并标记当前设备）
     */
    @GetMapping("/sessions")
    public Map<String, Object> sessions(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        result.put("success", true);
        result.put("sessions", storageService.listUserSessions(user.getId(), extractToken(request), getClientBrowser(request)));
        return result;
    }

    /**
     * 登录设备管理：踢掉指定会话（仅限本人的其他设备，不能踢掉当前设备）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> kickSession(@PathVariable String sessionId, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        int code = storageService.kickSession(user.getId(), sessionId, extractToken(request));
        if (code == 0) {
            result.put("success", true);
            result.put("message", "已踢下线");
        } else if (code == 2) {
            result.put("success", false);
            result.put("message", "不能踢掉当前设备，如需退出请使用退出登录");
        } else {
            result.put("success", false);
            result.put("message", "会话不存在或已下线");
        }
        return result;
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }

        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        String confirmPassword = body.get("confirmPassword");

        if (oldPassword == null || newPassword == null || confirmPassword == null
                || oldPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            result.put("success", false);
            result.put("message", "密码不能为空");
            return result;
        }

        if (!newPassword.equals(confirmPassword)) {
            result.put("success", false);
            result.put("message", "两次输入的新密码不一致");
            return result;
        }

        if (!isPasswordStrong(newPassword)) {
            result.put("success", false);
            result.put("message", "新密码长度至少 8 位，且需同时包含字母和数字");
            return result;
        }

        if (oldPassword.equals(newPassword)) {
            result.put("success", false);
            result.put("message", "新密码不能与旧密码相同");
            return result;
        }

        int code = storageService.changePassword(user.getId(), oldPassword, newPassword);
        if (code == 0) {
            result.put("success", true);
            result.put("message", "密码修改成功，请重新登录");
        } else if (code == 2) {
            result.put("success", false);
            result.put("message", "旧密码错误");
        } else {
            result.put("success", false);
            result.put("message", "用户不存在");
        }
        return result;
    }

    private String getClientIp(HttpServletRequest request) {
        return com.chatai.newbot.config.IpUtils.getClientIp(request);
    }

    /**
     * 密码强度校验：至少 8 位，且同时包含字母与数字，降低弱口令被爆破风险。
     */
    private boolean isPasswordStrong(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }

    /**
     * 从请求中提取 Bearer token（与 AuthInterceptor 一致，兼容 Cookie）
     */
    private String extractToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            return token.substring(7);
        }
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
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

