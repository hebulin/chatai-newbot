package com.chatai.newbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破服务（内存计数）
 * 策略：
 * - 按 用户名+IP 维度：连续失败 5 次锁定 10 分钟（防针对单账户的密码爆破）
 * - 按 IP 维度：连续失败 20 次锁定 30 分钟（防单 IP 撞库扫描多个账户）
 * - 登录成功即清空对应计数；失败计数窗口 15 分钟，超时自动重置
 * 计数仅存内存，服务重启后清零（可接受：重启本身即打断爆破节奏）。
 */
@Component
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 用户名+IP 维度：最大连续失败次数 */
    private static final int USER_MAX_FAILURES = 5;
    /** 用户名+IP 维度：锁定时长（毫秒） */
    private static final long USER_LOCK_MS = 10 * 60 * 1000L;
    /** IP 维度：最大连续失败次数 */
    private static final int IP_MAX_FAILURES = 20;
    /** IP 维度：锁定时长（毫秒） */
    private static final long IP_LOCK_MS = 30 * 60 * 1000L;
    /** 失败计数窗口（毫秒）：距上次失败超过该时长则重置计数 */
    private static final long FAILURE_WINDOW_MS = 15 * 60 * 1000L;

    /** 失败记录：key = "u:用户名|IP" 或 "ip:IP" */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** 单个维度的失败计数记录 */
    private static class Attempt {
        int failures;       // 窗口内连续失败次数
        long lastFailAt;    // 上次失败时间戳
        long lockUntil;     // 锁定截止时间戳（0=未锁定）
    }

    /**
     * 检查是否处于锁定状态
     * @param username 登录用户名
     * @param ip 客户端IP
     * @return 剩余锁定秒数，0 表示未锁定
     */
    public long getLockRemainSeconds(String username, String ip) {
        long now = System.currentTimeMillis();
        long remain = Math.max(lockRemain(userKey(username, ip), now), lockRemain(ipKey(ip), now));
        return (remain + 999) / 1000;
    }

    /**
     * 记录一次登录失败
     * @param username 登录用户名
     * @param ip 客户端IP
     */
    public void onFailure(String username, String ip) {
        long now = System.currentTimeMillis();
        recordFailure(userKey(username, ip), now, USER_MAX_FAILURES, USER_LOCK_MS,
                "账户[" + username + "] IP[" + ip + "]");
        recordFailure(ipKey(ip), now, IP_MAX_FAILURES, IP_LOCK_MS, "IP[" + ip + "]");
    }

    /**
     * 记录登录成功，清空对应维度的失败计数
     * @param username 登录用户名
     * @param ip 客户端IP
     */
    public void onSuccess(String username, String ip) {
        attempts.remove(userKey(username, ip));
        attempts.remove(ipKey(ip));
        // 顺带清理过期条目，防止 map 无限增长
        cleanExpired();
    }

    /** 计算指定 key 的剩余锁定毫秒数 */
    private long lockRemain(String key, long now) {
        Attempt a = attempts.get(key);
        if (a == null) return 0;
        synchronized (a) {
            return Math.max(0, a.lockUntil - now);
        }
    }

    /** 累加失败计数，达到阈值则锁定 */
    private void recordFailure(String key, long now, int maxFailures, long lockMs, String desc) {
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (a) {
            // 超出计数窗口则重置
            if (now - a.lastFailAt > FAILURE_WINDOW_MS) {
                a.failures = 0;
            }
            a.failures++;
            a.lastFailAt = now;
            if (a.failures >= maxFailures && a.lockUntil < now) {
                a.lockUntil = now + lockMs;
                log.warn("登录失败次数达到阈值，已锁定: {} （{}次，锁定{}分钟）", desc, a.failures, lockMs / 60000);
            }
        }
    }

    /** 清理已过期（无锁定且超出窗口）的条目 */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            synchronized (a) {
                return a.lockUntil < now && now - a.lastFailAt > FAILURE_WINDOW_MS;
            }
        });
    }

    /** 用户名+IP 维度 key（用户名不区分大小写） */
    private String userKey(String username, String ip) {
        return "u:" + (username == null ? "" : username.toLowerCase()) + "|" + ip;
    }

    /** IP 维度 key */
    private String ipKey(String ip) {
        return "ip:" + ip;
    }
}
