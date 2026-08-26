package com.chatai.newbot.controller;

import com.chatai.newbot.model.User;
import com.chatai.newbot.service.AuditLogService;
import com.chatai.newbot.service.LoginAttemptService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.ApiKeyCrypto;
import com.chatai.newbot.service.TwoFactorAuthService;
import com.chatai.newbot.service.RegistrationChallengeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    // Cookie 有效期与服务端 token TTL 一致（7 天，服务端过半自动续期）
    private static final long TOKEN_COOKIE_MAX_AGE_SECONDS = 7L * 24 * 60 * 60;
    private final StorageManager storageService;
    private final LoginAttemptService loginAttemptService;
    private final AuditLogService auditLogService;
    private final TwoFactorAuthService twoFactorAuthService;
    private final RegistrationChallengeService registrationChallengeService;

    /** 注入认证、审计、双重验证与注册挑战服务。 */
    public AuthController(StorageManager storageService, LoginAttemptService loginAttemptService,
                          AuditLogService auditLogService, TwoFactorAuthService twoFactorAuthService,
                          RegistrationChallengeService registrationChallengeService) {
        this.storageService = storageService;
        this.loginAttemptService = loginAttemptService;
        this.auditLogService = auditLogService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.registrationChallengeService = registrationChallengeService;
    }

    /**
     * 获取公开注册配置；验证码启用时同时签发一次性挑战。
     */
    @GetMapping("/register-config")
    public Map<String, Object> getRegisterConfig() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        boolean captchaEnabled = storageService.getRegistrationCaptchaEnabled();
        data.put("enabled", storageService.getRegistrationEnabled());
        data.put("inviteRequired", storageService.hasRegistrationInviteCode());
        data.put("captchaEnabled", captchaEnabled);
        if (captchaEnabled) data.put("captcha", registrationChallengeService.createChallenge());
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request,
                                     HttpServletResponse response) {
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
            auditLogService.record(null, username.trim(), "login.fail", "用户名或密码错误", ip);
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }
        // 被禁用账号拒绝登录（不计入爆破失败次数，避免误锁）
        if (user.isDisabled()) {
            auditLogService.record(user.getId(), user.getUsername(), "login.fail", "账号已被禁用", ip);
            result.put("success", false);
            result.put("message", "账号已被禁用，请联系管理员");
            return result;
        }
        String browser = getClientBrowser(request);
        if (user.isTwoFactorEnabled()) {
            String challengeToken = twoFactorAuthService.createLoginChallenge(user.getId(), ip, browser);
            result.put("success", true);
            result.put("requiresTwoFactor", true);
            result.put("challengeToken", challengeToken);
            result.put("username", user.getUsername());
            return result;
        }
        return completeLogin(user, request, response, browser);
    }

    /**
     * 完成登录二次验证并在验证成功后签发正式会话。
     */
    @PostMapping("/2fa/verify-login")
    public Map<String, Object> verifyTwoFactorLogin(@RequestBody Map<String, String> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        String challengeToken = body.get("challengeToken");
        String code = body.get("code");
        String method = body.getOrDefault("method", "totp");
        if (challengeToken == null || challengeToken.isBlank() || code == null || code.isBlank()) {
            result.put("success", false);
            result.put("message", "请输入双重验证码");
            return result;
        }

        String ip = getClientIp(request);
        TwoFactorAuthService.LoginChallengeData challenge =
                twoFactorAuthService.beginLoginVerification(challengeToken, ip);
        if (challenge == null) {
            result.put("success", false);
            result.put("challengeExpired", true);
            result.put("message", "验证请求已过期，请重新输入用户名和密码");
            return result;
        }

        boolean success = false;
        try {
            User user = storageService.getUserById(challenge.userId());
            if (user == null || user.isDisabled() || !user.isTwoFactorEnabled()) {
                twoFactorAuthService.finishLoginVerification(challengeToken, false);
                result.put("success", false);
                result.put("challengeExpired", true);
                result.put("message", "账号状态已变化，请重新登录");
                return result;
            }

            if ("recovery".equals(method)) {
                String normalized = twoFactorAuthService.normalizeRecoveryCode(code);
                success = normalized.matches("[A-Z2-7]{16}")
                        && storageService.consumeRecoveryCode(user.getId(),
                        twoFactorAuthService.hashRecoveryCode(normalized));
            } else {
                String secret = ApiKeyCrypto.decrypt(user.getTwoFactorSecret());
                OptionalLong step = twoFactorAuthService.findValidTotpStep(
                        secret, code, user.getTwoFactorLastUsedStep());
                success = step.isPresent() && storageService.claimTwoFactorStep(user.getId(), step.getAsLong());
            }

            if (success) {
                twoFactorAuthService.finishLoginVerification(challengeToken, true);
                return completeLogin(user, request, response, challenge.browser());
            }
            int remaining = twoFactorAuthService.finishLoginVerification(challengeToken, false);
            loginAttemptService.onFailure(user.getUsername(), ip);
            auditLogService.record(user.getId(), user.getUsername(), "双重验证登录失败",
                    "双重验证失败", ip);
            result.put("success", false);
            result.put("remainingAttempts", remaining);
            result.put("challengeExpired", remaining == 0);
            result.put("message", remaining > 0
                    ? "验证码无效，还可尝试 " + remaining + " 次"
                    : "验证失败次数过多，请重新登录");
            return result;
        } catch (Exception e) {
            if (!success) {
                twoFactorAuthService.finishLoginVerification(challengeToken, false);
            }
            log.warn("双重验证登录失败", e);
            result.put("success", false);
            result.put("message", "双重验证失败，请重试");
            return result;
        }
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body, HttpServletRequest request,
                                        HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        String username = body.get("username");
        String password = body.get("password");
        String ip = getClientIp(request);

        if (!storageService.getRegistrationEnabled()) {
            result.put("success", false);
            result.put("message", "系统当前已关闭注册");
            return result;
        }

        if (!storageService.matchesRegistrationInviteCode(body.get("inviteCode"))) {
            result.put("success", false);
            result.put("message", "邀请码无效");
            return result;
        }

        if (storageService.getRegistrationCaptchaEnabled()
                && !registrationChallengeService.verify(body.get("captchaId"), body.get("captchaAnswer"))) {
            result.put("success", false);
            result.put("message", "验证码无效或已过期，请刷新后重试");
            return result;
        }

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
        auditLogService.record(user.getId(), user.getUsername(), "register", "注册新账号", ip);
        setTokenCookie(response, token, TOKEN_COOKIE_MAX_AGE_SECONDS, isSecureRequest(request));
        result.put("success", true);
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        storageService.removeToken(extractToken(request));
        User user = (User) request.getAttribute("currentUser");
        if (user != null) {
            auditLogService.record(user.getId(), user.getUsername(), "logout", "退出登录", getClientIp(request));
        }
        // 清除登录 Cookie（Max-Age=0 立即失效）
        setTokenCookie(response, "", 0, isSecureRequest(request));
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
            auditLogService.record(user.getId(), user.getUsername(), "password.change", "修改本人密码", getClientIp(request));
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

    /**
     * 查询当前账号的双重验证状态，不暴露密钥或恢复码摘要。
     */
    @GetMapping("/2fa/status")
    public Map<String, Object> twoFactorStatus(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        User user = current == null ? null : storageService.getUserById(current.getId());
        if (user == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        result.put("success", true);
        result.put("enabled", user.isTwoFactorEnabled());
        result.put("recoveryCodesRemaining", user.getRecoveryCodeHashes() == null
                ? 0 : user.getRecoveryCodeHashes().size());
        return result;
    }

    /**
     * 验证当前密码并创建短期扫码绑定数据，尚不真正启用双重验证。
     */
    @PostMapping("/2fa/setup")
    public Map<String, Object> setupTwoFactor(@RequestBody Map<String, String> body,
                                              HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        String password = body.get("password");
        if (current == null || password == null || password.isBlank()) {
            result.put("success", false);
            result.put("message", "请输入当前密码");
            return result;
        }
        String ip = getClientIp(request);
        long lockRemain = loginAttemptService.getLockRemainSeconds(current.getUsername(), ip);
        if (lockRemain > 0) {
            result.put("success", false);
            result.put("message", "验证失败次数过多，请稍后重试");
            return result;
        }
        User user = storageService.authenticate(current.getUsername(), password);
        if (user == null) {
            loginAttemptService.onFailure(current.getUsername(), ip);
            result.put("success", false);
            result.put("message", "当前密码错误");
            return result;
        }
        loginAttemptService.onSuccess(current.getUsername(), ip);
        if (user.isTwoFactorEnabled()) {
            result.put("success", false);
            result.put("message", "双重验证已开启");
            return result;
        }
        TwoFactorAuthService.SetupData setup =
                twoFactorAuthService.createSetup(user.getId(), user.getUsername());
        result.put("success", true);
        result.put("setupToken", setup.setupToken());
        result.put("secret", setup.secret());
        result.put("provisioningUri", setup.provisioningUri());
        return result;
    }

    /**
     * 校验身份验证器中的首次 TOTP，并正式启用双重验证、生成 20 组恢复码。
     */
    @PostMapping("/2fa/enable")
    public Map<String, Object> enableTwoFactor(@RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        if (current == null) {
            result.put("success", false);
            result.put("message", "未登录");
            return result;
        }
        String secret = twoFactorAuthService.consumeSetup(
                current.getId(), body.get("setupToken"), body.get("code"));
        if (secret == null) {
            loginAttemptService.onFailure(current.getUsername(), getClientIp(request));
            result.put("success", false);
            result.put("message", "验证码无效或绑定请求已过期，请重试");
            return result;
        }
        List<String> recoveryCodes = twoFactorAuthService.generateRecoveryCodes();
        boolean enabled = storageService.enableTwoFactor(current.getId(), ApiKeyCrypto.encrypt(secret),
                twoFactorAuthService.hashRecoveryCodes(recoveryCodes));
        if (!enabled) {
            result.put("success", false);
            result.put("message", "双重验证状态已变化，请刷新后重试");
            return result;
        }
        loginAttemptService.onSuccess(current.getUsername(), getClientIp(request));
        auditLogService.record(current.getId(), current.getUsername(), "开启双重验证",
                "开启双重验证", getClientIp(request));
        storageService.removeOtherTokensByUserId(current.getId(), extractToken(request));
        result.put("success", true);
        result.put("message", "双重验证已开启");
        result.put("recoveryCodes", recoveryCodes);
        return result;
    }

    /**
     * 验证当前密码后关闭双重验证，并清除密钥与全部恢复码。
     */
    @PostMapping("/2fa/disable")
    public Map<String, Object> disableTwoFactor(@RequestBody Map<String, String> body,
                                                HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        String password = body.get("password");
        if (current == null || password == null || password.isBlank()) {
            result.put("success", false);
            result.put("message", "请输入当前密码");
            return result;
        }
        String ip = getClientIp(request);
        long lockRemain = loginAttemptService.getLockRemainSeconds(current.getUsername(), ip);
        if (lockRemain > 0) {
            result.put("success", false);
            result.put("message", "验证失败次数过多，请稍后重试");
            return result;
        }
        if (storageService.authenticate(current.getUsername(), password) == null) {
            loginAttemptService.onFailure(current.getUsername(), ip);
            result.put("success", false);
            result.put("message", "当前密码错误");
            return result;
        }
        loginAttemptService.onSuccess(current.getUsername(), ip);
        if (!storageService.disableTwoFactor(current.getId())) {
            result.put("success", false);
            result.put("message", "用户不存在");
            return result;
        }
        auditLogService.record(current.getId(), current.getUsername(), "关闭双重验证",
                "关闭双重验证", getClientIp(request));
        storageService.removeOtherTokensByUserId(current.getId(), extractToken(request));
        result.put("success", true);
        result.put("message", "双重验证已关闭");
        return result;
    }

    /**
     * 验证当前密码和 TOTP 后重置 20 组恢复码，旧码立即全部失效。
     */
    @PostMapping("/2fa/recovery-codes/regenerate")
    public Map<String, Object> regenerateRecoveryCodes(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User current = (User) request.getAttribute("currentUser");
        String password = body.get("password");
        String code = body.get("code");
        User user = current == null ? null : storageService.getUserById(current.getId());
        if (user == null || !user.isTwoFactorEnabled()) {
            result.put("success", false);
            result.put("message", "双重验证尚未开启");
            return result;
        }
        String ip = getClientIp(request);
        long lockRemain = loginAttemptService.getLockRemainSeconds(user.getUsername(), ip);
        if (lockRemain > 0) {
            result.put("success", false);
            result.put("message", "验证失败次数过多，请稍后重试");
            return result;
        }
        if (password == null || storageService.authenticate(user.getUsername(), password) == null) {
            loginAttemptService.onFailure(user.getUsername(), ip);
            result.put("success", false);
            result.put("message", "当前密码错误");
            return result;
        }
        OptionalLong step = twoFactorAuthService.findValidTotpStep(
                ApiKeyCrypto.decrypt(user.getTwoFactorSecret()), code, user.getTwoFactorLastUsedStep());
        if (step.isEmpty() || !storageService.claimTwoFactorStep(user.getId(), step.getAsLong())) {
            loginAttemptService.onFailure(user.getUsername(), ip);
            result.put("success", false);
            result.put("message", "双重验证码无效");
            return result;
        }
        loginAttemptService.onSuccess(user.getUsername(), ip);
        List<String> recoveryCodes = twoFactorAuthService.generateRecoveryCodes();
        if (!storageService.replaceRecoveryCodes(
                user.getId(), twoFactorAuthService.hashRecoveryCodes(recoveryCodes))) {
            result.put("success", false);
            result.put("message", "恢复码保存失败，请重试");
            return result;
        }
        auditLogService.record(user.getId(), user.getUsername(), "重新生成恢复码",
                "重新生成恢复码", getClientIp(request));
        result.put("success", true);
        result.put("recoveryCodes", recoveryCodes);
        result.put("message", "恢复码已重新生成");
        return result;
    }

    /**
     * 在所有认证步骤完成后统一创建登录会话与安全 Cookie。
     */
    private Map<String, Object> completeLogin(User user, HttpServletRequest request,
                                               HttpServletResponse response, String browser) {
        String ip = getClientIp(request);
        loginAttemptService.onSuccess(user.getUsername(), ip);
        storageService.updateLoginInfo(user.getId(), ip, browser);
        String token = storageService.createToken(user.getId(), ip, browser);
        auditLogService.record(user.getId(), user.getUsername(), "login", "登录成功（" + browser + "）", ip);
        setTokenCookie(response, token, TOKEN_COOKIE_MAX_AGE_SECONDS, isSecureRequest(request));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    private String getClientIp(HttpServletRequest request) {
        return com.chatai.newbot.config.IpUtils.getClientIp(request);
    }

    /**
     * 将登录 token 写入 HttpOnly Cookie：JS 不可读（防 XSS 窃取），SameSite=Lax 缓解 CSRF；
     * secure 根据当前请求是否走 HTTPS 动态设置：HTTPS 下附加 Secure 防止明文传输被窃听，
     * 纯 HTTP 部署时不加 Secure 以免 Cookie 无法下发。
     */
    private void setTokenCookie(HttpServletResponse response, String token, long maxAgeSeconds, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from("token", token == null ? "" : token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 判断当前请求是否为 HTTPS：直连 HTTPS（request.isSecure）或反向代理终结 TLS 后
     * 通过 X-Forwarded-Proto 传递的 https。据此决定是否为登录 Cookie 附加 Secure 标志。
     */
    private boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        if (!com.chatai.newbot.config.IpUtils.trustAllProxies()
                && !com.chatai.newbot.config.IpUtils.isTrustedProxy(request.getRemoteAddr())) {
            return false;
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && "https".equalsIgnoreCase(proto.split(",")[0].trim());
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

