package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 双重验证核心服务：负责 TOTP、恢复码以及短期登录/绑定挑战。
 * 所有挑战只保存在进程内并设短 TTL，服务重启后自动失效，不形成长期旁路凭据。
 */
@Service
public class TwoFactorAuthService {
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TOTP_DIGITS = 6;
    private static final long TOTP_PERIOD_SECONDS = 30L;
    private static final int ALLOWED_TIME_DRIFT_STEPS = 1;
    private static final long LOGIN_CHALLENGE_TTL_SECONDS = 5L * 60L;
    private static final long SETUP_CHALLENGE_TTL_SECONDS = 10L * 60L;
    private static final int MAX_CHALLENGE_ATTEMPTS = 5;
    private static final int RECOVERY_CODE_COUNT = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, LoginChallenge> loginChallenges = new ConcurrentHashMap<>();
    private final Map<String, SetupChallenge> setupChallenges = new ConcurrentHashMap<>();

    /**
     * 创建一次扫码绑定挑战。
     * @param userId 当前用户ID
     * @param username 当前用户名
     * @return 绑定令牌、Base32 密钥和 otpauth 地址
     */
    public SetupData createSetup(String userId, String username) {
        purgeExpiredChallenges();
        String token = randomToken(32);
        String secret = base32Encode(randomBytes(20));
        long expiresAt = Instant.now().getEpochSecond() + SETUP_CHALLENGE_TTL_SECONDS;
        setupChallenges.put(token, new SetupChallenge(userId, secret, expiresAt));
        return new SetupData(token, secret, buildProvisioningUri(username, secret));
    }

    /**
     * 校验绑定挑战中的首次验证码，成功后一次性取出对应密钥。
     * @param userId 当前用户ID
     * @param setupToken 绑定挑战令牌
     * @param code 六位 TOTP 验证码
     * @return 成功返回明文密钥，失败返回 null
     */
    public synchronized String consumeSetup(String userId, String setupToken, String code) {
        purgeExpiredChallenges();
        SetupChallenge challenge = setupChallenges.get(setupToken);
        if (challenge == null || !challenge.userId.equals(userId)) {
            return null;
        }
        if (findValidTotpStep(challenge.secret, code, -1L).isPresent()) {
            setupChallenges.remove(setupToken);
            return challenge.secret;
        }
        if (challenge.attempts.incrementAndGet() >= MAX_CHALLENGE_ATTEMPTS) {
            setupChallenges.remove(setupToken);
        }
        return null;
    }

    /**
     * 创建登录二次验证挑战，并绑定首次登录请求的 IP 与浏览器信息。
     * @param userId 用户ID
     * @param ip 客户端IP
     * @param browser 浏览器标识
     * @return 不可预测的挑战令牌
     */
    public String createLoginChallenge(String userId, String ip, String browser) {
        purgeExpiredChallenges();
        String token = randomToken(32);
        long expiresAt = Instant.now().getEpochSecond() + LOGIN_CHALLENGE_TTL_SECONDS;
        loginChallenges.put(token, new LoginChallenge(userId, ip, browser, expiresAt));
        return token;
    }

    /**
     * 原子取得并锁定一次登录挑战，避免同一挑战被并发重复验证。
     * @param token 登录挑战令牌
     * @param ip 当前客户端IP
     * @return 可验证的挑战数据；无效、过期、IP不匹配或正在验证时返回 null
     */
    public synchronized LoginChallengeData beginLoginVerification(String token, String ip) {
        purgeExpiredChallenges();
        LoginChallenge challenge = loginChallenges.get(token);
        if (challenge == null || challenge.inProgress || !MessageDigest.isEqual(
                challenge.ip.getBytes(StandardCharsets.UTF_8), safe(ip).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        challenge.inProgress = true;
        return new LoginChallengeData(challenge.userId, challenge.browser);
    }

    /**
     * 结束登录挑战验证；成功立即销毁，失败累计次数并在达到上限时销毁。
     * @param token 登录挑战令牌
     * @param success 本次验证是否成功
     * @return 剩余可尝试次数；成功或挑战已失效时返回 0
     */
    public synchronized int finishLoginVerification(String token, boolean success) {
        LoginChallenge challenge = loginChallenges.get(token);
        if (challenge == null) {
            return 0;
        }
        if (success) {
            loginChallenges.remove(token);
            return 0;
        }
        challenge.inProgress = false;
        int used = challenge.attempts.incrementAndGet();
        if (used >= MAX_CHALLENGE_ATTEMPTS) {
            loginChallenges.remove(token);
            return 0;
        }
        return MAX_CHALLENGE_ATTEMPTS - used;
    }

    /**
     * 在允许一个时间步时钟偏差的范围内查找有效且未使用的 TOTP 时间步。
     * @param base32Secret Base32 编码密钥
     * @param code 用户输入的六位验证码
     * @param lastUsedStep 最近已使用时间步
     * @return 匹配的时间步；无匹配返回空
     */
    public OptionalLong findValidTotpStep(String base32Secret, String code, long lastUsedStep) {
        String normalized = code == null ? "" : code.replaceAll("\\s+", "");
        if (!normalized.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long currentStep = Instant.now().getEpochSecond() / TOTP_PERIOD_SECONDS;
        for (int drift = ALLOWED_TIME_DRIFT_STEPS; drift >= -ALLOWED_TIME_DRIFT_STEPS; drift--) {
            long step = currentStep + drift;
            if (step <= lastUsedStep) {
                continue;
            }
            String expected = generateTotp(base32Secret, step);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    normalized.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * 生成 20 个高熵、可读的一次性恢复码。
     * @return 恢复码明文列表，仅应在创建或重置时展示一次
     */
    public List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String raw = base32Encode(randomBytes(10));
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-"
                    + raw.substring(8, 12) + "-" + raw.substring(12, 16));
        }
        return codes;
    }

    /**
     * 将恢复码标准化后计算 SHA-256 摘要。
     * @param code 用户输入或刚生成的恢复码
     * @return 小写十六进制摘要
     */
    public String hashRecoveryCode(String code) {
        try {
            String normalized = normalizeRecoveryCode(code);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("恢复码摘要计算失败", e);
        }
    }

    /**
     * 批量计算恢复码摘要，供持久化使用。
     * @param codes 恢复码明文列表
     * @return 摘要列表
     */
    public List<String> hashRecoveryCodes(List<String> codes) {
        return codes.stream().map(this::hashRecoveryCode).toList();
    }

    /**
     * 规范化恢复码：忽略空格和连字符并统一大写。
     * @param code 恢复码输入
     * @return 用于校验的标准形式
     */
    public String normalizeRecoveryCode(String code) {
        return safe(code).replace("-", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * 根据 RFC 6238 生成指定时间步的六位 TOTP。
     */
    String generateTotp(String base32Secret, long step) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, TOTP_DIGITS);
            return String.format(Locale.ROOT, "%0" + TOTP_DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    /**
     * 构建身份验证器应用可识别的 otpauth URI。
     */
    private String buildProvisioningUri(String username, String secret) {
        String issuer = "ChatAI";
        String label = urlEncode(issuer + ":" + safe(username));
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /**
     * 按查询参数规则进行 UTF-8 URL 编码。
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 生成 URL 安全的随机挑战令牌。
     */
    private String randomToken(int byteLength) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(byteLength));
    }

    /**
     * 生成指定长度的安全随机字节。
     */
    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 将随机字节编码为无填充 Base32 字符串。
     */
    private String base32Encode(byte[] input) {
        StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return output.toString();
    }

    /**
     * 解码无填充 Base32 字符串。
     */
    private byte[] base32Decode(String encoded) {
        String normalized = safe(encoded).replace("=", "").replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("无效的 Base32 密钥");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return output;
    }

    /**
     * 清理所有已过期挑战，限制内存占用并缩短凭据生命周期。
     */
    private synchronized void purgeExpiredChallenges() {
        long now = Instant.now().getEpochSecond();
        loginChallenges.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        setupChallenges.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    /**
     * 将 null 字符串归一化为空串。
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 绑定页面所需的短期数据。 */
    public record SetupData(String setupToken, String secret, String provisioningUri) { }

    /** 登录挑战校验后暴露给控制器的最小数据。 */
    public record LoginChallengeData(String userId, String browser) { }

    /** 进程内登录挑战。 */
    private static final class LoginChallenge {
        private final String userId;
        private final String ip;
        private final String browser;
        private final long expiresAt;
        private final AtomicInteger attempts = new AtomicInteger();
        private boolean inProgress;

        /** 初始化登录挑战。 */
        private LoginChallenge(String userId, String ip, String browser, long expiresAt) {
            this.userId = userId;
            this.ip = safeStatic(ip);
            this.browser = browser;
            this.expiresAt = expiresAt;
        }
    }

    /** 进程内扫码绑定挑战。 */
    private static final class SetupChallenge {
        private final String userId;
        private final String secret;
        private final long expiresAt;
        private final AtomicInteger attempts = new AtomicInteger();

        /** 初始化扫码绑定挑战。 */
        private SetupChallenge(String userId, String secret, long expiresAt) {
            this.userId = userId;
            this.secret = secret;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 静态上下文使用的 null 字符串归一化方法。
     */
    private static String safeStatic(String value) {
        return value == null ? "" : value;
    }
}
