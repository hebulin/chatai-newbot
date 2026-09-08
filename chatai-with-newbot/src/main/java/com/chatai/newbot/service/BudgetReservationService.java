package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.NewBotMessage;
import com.chatai.newbot.model.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单节点预算预占服务。配额判断与预占在同一用户锁内完成，避免并发请求同时通过旧用量快照。
 */
@Service
public class BudgetReservationService {
    private static final int MAX_OUTPUT_TOKENS = 32_000;
    private final StorageManager storageManager;
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    /** 注入用量统计与配额设置存储。 */
    public BudgetReservationService(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * 校验个人/全局配额并预占一次请求的最大用量。
     * @param user 当前用户
     * @param request 聊天请求
     * @param model 模型配置
     * @return 允许结果（含 requestId）或拒绝原因
     */
    public ReservationResult reserve(User user, ChatRequest request, ModelConfig model) {
        int maxOutput = Math.max(1, Math.min(
                request.getMax_tokens() <= 0 ? 6000 : request.getMax_tokens(), MAX_OUTPUT_TOKENS));
        request.setMax_tokens(maxOutput);
        long estimatedInput = estimateInputTokens(request);
        long estimatedTokens = estimatedInput + maxOutput;
        double estimatedCost = estimateCost(model, estimatedInput, maxOutput);
        String day = LocalDate.now().toString();
        Object lock = userLocks.computeIfAbsent(user.getId(), ignored -> new Object());
        synchronized (lock) {
            PendingTotals pending = pendingTotals(user.getId());
            int usedCount = storageManager.countUsageByUserAndDay(user.getId(), day) + pending.count();
            long usedTokens = storageManager.sumTokensByUserAndDay(user.getId(), day) + pending.tokens();
            double usedCost = storageManager.sumCostCnyByUserAndDay(user.getId(), day) + pending.costCny();

            String personalType = user.getDailyLimitType();
            int personalValue = user.getDailyLimitValue();
            if ("count".equals(personalType) && personalValue > 0 && usedCount + 1 > personalValue) {
                return ReservationResult.denied("今日调用次数已达上限（" + personalValue + " 次），请明日再试");
            }
            if ("token".equals(personalType) && personalValue > 0
                    && usedTokens + estimatedTokens > personalValue) {
                return ReservationResult.denied("本次请求可能超过今日 Token 上限（" + personalValue + "），请缩短上下文或输出长度");
            }
            if ((personalType == null || personalType.isBlank() || personalValue <= 0)) {
                int countLimit = storageManager.getDailyChatLimit();
                long tokenLimit = storageManager.getDailyTokenLimit();
                double costLimit = storageManager.getDailyCostLimitCny();
                if (countLimit > 0 && usedCount + 1 > countLimit) {
                    return ReservationResult.denied("今日调用次数已达上限（" + countLimit + " 次），请明日再试");
                }
                if (tokenLimit > 0 && usedTokens + estimatedTokens > tokenLimit) {
                    return ReservationResult.denied("本次请求可能超过今日 Token 总配额，请缩短上下文或输出长度");
                }
                if (costLimit > 0 && usedCost + estimatedCost > costLimit) {
                    return ReservationResult.denied("本次请求可能超过今日金额预算，请缩短上下文或改用低成本模型");
                }
            }

            String requestId = UUID.randomUUID().toString();
            reservations.put(requestId,
                    new Reservation(requestId, user.getId(), estimatedTokens, estimatedCost));
            return ReservationResult.allowed(requestId);
        }
    }

    /**
     * 流结束时释放预占；实际用量由 UnifiedChatService 持久化。
     * @param requestId 请求唯一 ID
     */
    public void release(String requestId) {
        if (requestId != null) reservations.remove(requestId);
    }

    /**
     * 估算请求输入 Token，文本按 4 字符/Token 近似，图片按固定 1024 Token 预留。
     */
    private long estimateInputTokens(ChatRequest request) {
        long chars = 0;
        long images = 0;
        if (request.getMessages() != null) {
            for (NewBotMessage message : request.getMessages()) {
                if (message != null && message.getContent() != null) chars += message.getContent().length();
                if (message != null && message.getImages() != null) images += message.getImages().size();
            }
        }
        return Math.max(1, (chars + 3) / 4) + images * 1024;
    }

    /**
     * 按模型单价估算本次请求的最大人民币成本。
     */
    private double estimateCost(ModelConfig model, long inputTokens, long outputTokens) {
        double inputPrice = Math.max(0D, model.getInputPriceCny());
        double outputPrice = Math.max(0D, model.getOutputPriceCny());
        return (inputTokens * inputPrice + outputTokens * outputPrice) / 1_000_000D;
    }

    /**
     * 汇总指定用户所有尚未结束请求的预占量。
     */
    private PendingTotals pendingTotals(String userId) {
        int count = 0;
        long tokens = 0;
        double cost = 0D;
        for (Reservation reservation : reservations.values()) {
            if (!userId.equals(reservation.userId())) continue;
            count++;
            tokens += reservation.tokens();
            cost += reservation.costCny();
        }
        return new PendingTotals(count, tokens, cost);
    }

    private record Reservation(String requestId, String userId, long tokens, double costCny) { }
    private record PendingTotals(int count, long tokens, double costCny) { }

    /** 预算预占结果。 */
    public record ReservationResult(boolean allowed, String requestId, String message) {
        /** 构造允许结果。 */
        public static ReservationResult allowed(String requestId) {
            return new ReservationResult(true, requestId, null);
        }

        /** 构造拒绝结果。 */
        public static ReservationResult denied(String message) {
            return new ReservationResult(false, null, message);
        }
    }
}
