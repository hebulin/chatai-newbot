package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短时限流服务 - 每用户每分钟滑动窗口。
 * 纯内存实现（无需持久化）：为每个用户维护最近 60 秒内的请求时间戳队列，
 * 每次请求先清理过期时间戳，再判断是否超过上限。服务重启后计数自然清零。
 */
@Service
public class RateLimitService {
    private static final long WINDOW_MILLIS = 60_000L;

    /** userId -> 最近一分钟内的请求时间戳（毫秒），队列头为最早 */
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * 尝试为指定用户放行一次请求（滑动窗口计数）。
     * @param userId 用户ID
     * @param limitPerMinute 每分钟上限，<=0 表示不限制
     * @return true=放行（已计数）；false=超过上限，应拒绝
     */
    public boolean tryAcquire(String userId, int limitPerMinute) {
        if (limitPerMinute <= 0 || userId == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (queue) {
            // 清理窗口外的时间戳
            long threshold = now - WINDOW_MILLIS;
            Iterator<Long> it = queue.iterator();
            while (it.hasNext()) {
                if (it.next() < threshold) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (queue.size() >= limitPerMinute) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }
}
