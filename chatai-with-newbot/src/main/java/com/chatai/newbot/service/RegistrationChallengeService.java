package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 注册算术验证码服务，挑战一次性使用并在五分钟后失效。 */
@Service
public class RegistrationChallengeService {
    private static final long TTL_SECONDS = 300;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    /**
     * 创建一个新的算术验证码挑战。
     * @return 只包含 challengeId、question 和有效期的公开数据
     */
    public Map<String, Object> createChallenge() {
        cleanupExpired();
        int left = random.nextInt(8) + 2;
        int right = random.nextInt(8) + 1;
        String id = UUID.randomUUID().toString();
        long expiresAt = Instant.now().plusSeconds(TTL_SECONDS).toEpochMilli();
        challenges.put(id, new Challenge(left + right, expiresAt));
        return Map.of("challengeId", id, "question", left + " + " + right + " = ?",
                "expiresAt", expiresAt);
    }

    /**
     * 验证并消费挑战，任何成功或失败尝试都会使 challengeId 失效。
     * @param challengeId 挑战 ID
     * @param answer 用户答案
     * @return 是否正确且未过期
     */
    public boolean verify(String challengeId, String answer) {
        if (challengeId == null || challengeId.isBlank()) return false;
        Challenge challenge = challenges.remove(challengeId);
        if (challenge == null || challenge.expiresAt() < System.currentTimeMillis()) return false;
        try {
            return Integer.parseInt(answer == null ? "" : answer.trim()) == challenge.answer();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 清理过期挑战，限制内存占用。 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private record Challenge(int answer, long expiresAt) { }
}
