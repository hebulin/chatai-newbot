package com.chatai.newbot.controller;

import com.chatai.newbot.model.ChatShare;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.ChatHistoryService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.service.PasswordHasher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话分享接口
 * - 登录用户可为自己的会话生成只读分享链接、查看/撤销自己的分享
 * - 匿名访问 GET /api/share/view/{id} 凭分享码查看会话内容（拦截器已豁免）
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final StorageManager storageManager;
    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern API_KEY = Pattern.compile("(?i)(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]{12,})");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");

    public ShareController(StorageManager storageManager, ChatHistoryService chatHistoryService) {
        this.storageManager = storageManager;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 获取当前用户创建的所有分享记录，附带失效状态判定（口径与后台分享管理一致）
     * status: valid=有效 expired=已过期 orphaned=源会话已被删除
     */
    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        List<ChatShare> shares = storageManager.getChatSharesByUser(user.getId());
        // 只做会话存在性检查，不加载消息正文
        List<String> chatIds = new ArrayList<>();
        for (ChatShare s : shares) chatIds.add(s.getChatId());
        Set<String> existingChatIds = chatHistoryService.filterExistingChats(user.getId(), chatIds);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChatShare s : shares) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("chatId", s.getChatId());
            item.put("userId", s.getUserId());
            item.put("userName", s.getUserName());
            item.put("title", s.getTitle());
            item.put("createdAt", s.getCreatedAt());
            item.put("expiresAt", s.getExpiresAt());
            item.put("accessCount", s.getAccessCount());
            item.put("maxViews", s.getMaxViews());
            item.put("passwordProtected", s.getPasswordHash() != null && !s.getPasswordHash().isBlank());
            item.put("sanitized", s.isSanitized());
            item.put("status", resolveShareStatus(s, existingChatIds));
            list.add(item);
        }
        result.put("success", true);
        result.put("data", list);
        return result;
    }

    /**
     * 为指定会话生成只读分享链接（同一会话复用已有分享码）
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();

        String chatId = body.get("chatId") instanceof String s ? s.trim() : "";
        if (chatId.isEmpty()) {
            result.put("success", false);
            result.put("message", "会话ID不能为空");
            return result;
        }

        // 校验会话属于当前用户且存在消息
        List<Map<String, Object>> messages = extractChatMessages(user.getId(), chatId);
        if (messages == null || messages.isEmpty()) {
            result.put("success", false);
            result.put("message", "会话不存在或没有消息，无法分享");
            return result;
        }

        // 解析过期天数（expireDays：<=0 或缺省=永久有效）
        int expireDays = 0;
        Object expireObj = body.get("expireDays");
        if (expireObj instanceof Number n) {
            expireDays = n.intValue();
        } else if (expireObj instanceof String es && !es.trim().isEmpty()) {
            try { expireDays = Integer.parseInt(es.trim()); } catch (NumberFormatException ignore) { }
        }
        String expiresAt = expireDays > 0
                ? LocalDateTime.now().plusDays(expireDays).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null;
        int maxViews = parseNonNegativeInt(body.get("maxViews"), 1_000_000);
        boolean sanitized = Boolean.TRUE.equals(body.get("sanitized"));
        String password = body.get("password") instanceof String s ? s.trim() : "";
        if (password.length() > 100) {
            return Map.of("success", false, "message", "分享密码不能超过 100 个字符");
        }
        List<Map<String, Object>> snapshot = sanitized ? sanitizeMessages(messages) : messages;
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return Map.of("success", false, "message", "生成分享快照失败");
        }

        // 同一会话已分享过则复用分享码，并按本次选择更新过期时间（重新分享可续期/改为永久）
        ChatShare existing = storageManager.getChatShareByChat(user.getId(), chatId);
        if (existing != null) {
            existing.setTitle(buildTitle(snapshot));
            existing.setExpiresAt(expiresAt);
            existing.setSnapshotJson(snapshotJson);
            existing.setPasswordHash(password.isEmpty() ? null : PasswordHasher.hash(password));
            existing.setMaxViews(maxViews);
            existing.setSanitized(sanitized);
            storageManager.updateChatShareDetails(existing);
            result.put("success", true);
            result.put("data", existing);
            return result;
        }

        ChatShare share = new ChatShare();
        share.setChatId(chatId);
        share.setUserId(user.getId());
        share.setUserName(user.getUsername());
        share.setTitle(buildTitle(messages));
        share.setExpiresAt(expiresAt);
        share.setSnapshotJson(snapshotJson);
        share.setPasswordHash(password.isEmpty() ? null : PasswordHasher.hash(password));
        share.setMaxViews(maxViews);
        share.setSanitized(sanitized);
        result.put("success", true);
        result.put("data", storageManager.addChatShare(share));
        return result;
    }

    /**
     * 撤销分享（仅创建者本人或管理员）
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();

        ChatShare share = storageManager.getChatShareById(id);
        if (share == null) {
            result.put("success", false);
            result.put("message", "分享不存在");
            return result;
        }
        if (!user.isAdmin() && !user.getId().equals(share.getUserId())) {
            result.put("success", false);
            result.put("message", "无权撤销该分享");
            return result;
        }
        storageManager.deleteChatShare(id);
        result.put("success", true);
        return result;
    }

    /**
     * 批量删除当前用户自己的分享（清除失效/批量撤销）。请求体: {"ids": ["..."]}
     */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Map<String, Object> result = new HashMap<>();
        Object idsObj = body == null ? null : body.get("ids");
        if (!(idsObj instanceof List<?> ids) || ids.isEmpty()) {
            result.put("success", false);
            result.put("message", "请指定要删除的分享");
            return result;
        }
        int deleted = 0;
        for (Object idObj : ids) {
            if (!(idObj instanceof String id) || id.isEmpty()) continue;
            ChatShare share = storageManager.getChatShareById(id);
            // 仅允许删除自己创建的分享
            if (share == null || !user.getId().equals(share.getUserId())) continue;
            if (storageManager.deleteChatShare(id)) deleted++;
        }
        result.put("success", true);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * 匿名查看分享的会话内容（只读，无需登录）
     */
    @GetMapping("/view/{id}")
    public Map<String, Object> view(@PathVariable String id) {
        return viewInternal(id, "");
    }

    /**
     * 使用访问密码读取分享；密码仅通过请求体传输，不进入 URL/访问日志。
     */
    @PostMapping("/view/{id}")
    public Map<String, Object> viewWithPassword(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        String password = body != null && body.get("password") != null
                ? String.valueOf(body.get("password")) : "";
        return viewInternal(id, password);
    }

    /**
     * 校验分享访问规则、原子计数并返回快照。
     */
    private Map<String, Object> viewInternal(String id, String password) {
        Map<String, Object> result = new HashMap<>();

        ChatShare share = storageManager.getChatShareById(id);
        if (share == null) {
            result.put("success", false);
            result.put("message", "分享链接不存在或已被撤销");
            return result;
        }

        // 过期校验：expiresAt 非空且已过期则拒绝访问
        if (isExpired(share.getExpiresAt())) {
            result.put("success", false);
            result.put("message", "分享链接已过期");
            return result;
        }

        if (share.getPasswordHash() != null && !share.getPasswordHash().isBlank()
                && !PasswordHasher.matches(password == null ? "" : password, share.getPasswordHash())) {
            result.put("success", false);
            result.put("passwordRequired", true);
            result.put("message", password == null || password.isEmpty() ? "请输入分享密码" : "分享密码错误");
            return result;
        }

        List<Map<String, Object>> messages = readShareSnapshot(share);
        if (messages == null || messages.isEmpty()) {
            result.put("success", false);
            result.put("message", "分享的会话已被删除");
            return result;
        }
        if (!storageManager.claimChatShareAccess(id)) {
            result.put("success", false);
            result.put("message", "分享链接访问次数已用完");
            return result;
        }

        result.put("success", true);
        result.put("title", share.getTitle());
        result.put("sharedBy", share.getUserName());
        result.put("sharedAt", share.getCreatedAt());
        result.put("expiresAt", share.getExpiresAt());
        result.put("accessCount", share.getAccessCount() + 1);
        result.put("maxViews", share.getMaxViews());
        result.put("sanitized", share.isSanitized());
        result.put("messages", withShareImageAccess(messages, id));
        return result;
    }

    /**
     * 将分享快照复制为当前登录用户的新会话分支。
     */
    @PostMapping("/{id}/clone")
    public Map<String, Object> cloneShare(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        String password = body != null && body.get("password") != null
                ? String.valueOf(body.get("password")) : "";
        ChatShare share = storageManager.getChatShareById(id);
        if (share == null || isExpired(share.getExpiresAt())) {
            return Map.of("success", false, "message", "分享不存在或已过期");
        }
        if (share.getPasswordHash() != null && !share.getPasswordHash().isBlank()
                && !PasswordHasher.matches(password, share.getPasswordHash())) {
            return Map.of("success", false, "message", "分享密码错误");
        }
        List<Map<String, Object>> messages = readShareSnapshot(share);
        if (messages == null || messages.isEmpty()) {
            return Map.of("success", false, "message", "分享快照不可用");
        }
        grantSnapshotAssets(messages, user.getId());
        String chatId = "share_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", share.getTitle() + " · 分享副本");
        meta.put("sourceShareId", share.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lastChatId", chatId);
        payload.put("chats", Map.of(chatId, messages));
        payload.put("chatMeta", Map.of(chatId, meta));
        long version = chatHistoryService.saveChatHistory(user.getId(), payload);
        return Map.of("success", true, "chatId", chatId, "version", version);
    }

    /** 将快照中的图片和文档资源授权给复制者，保持原始资源所有权不变。 */
    private void grantSnapshotAssets(List<Map<String, Object>> messages, String userId) {
        for (Map<String, Object> message : messages) {
            if (message.get("images") instanceof List<?> images) {
                images.stream().filter(String.class::isInstance).map(String.class::cast)
                        .forEach(url -> storageManager.grantFileAssetAccess(url, userId));
            }
            if (message.get("attachments") instanceof List<?> attachments) {
                for (Object attachment : attachments) {
                    if (attachment instanceof Map<?, ?> map && map.get("url") instanceof String url) {
                        storageManager.grantFileAssetAccess(url, userId);
                    }
                }
            }
        }
    }

    /**
     * 判断过期时间是否已到期（空/解析失败视为未设置过期）
     */
    private boolean isExpired(String expiresAt) {
        if (expiresAt == null || expiresAt.isEmpty()) return false;
        try {
            LocalDateTime expiry = LocalDateTime.parse(expiresAt, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return LocalDateTime.now().isAfter(expiry);
        } catch (Exception ignore) {
            return false;
        }
    }

    /**
     * 判定分享状态：已过期 > 源会话已删 > 有效（与后台分享管理口径一致）
     */
    private String resolveShareStatus(ChatShare s, Set<String> existingChatIds) {
        if (isExpired(s.getExpiresAt())) return "expired";
        if (s.getSnapshotJson() != null && !s.getSnapshotJson().isBlank()) return "valid";
        return existingChatIds.contains(s.getChatId()) ? "valid" : "orphaned";
    }

    /**
     * 优先读取不可变分享快照；历史分享没有快照时回退源会话。
     */
    private List<Map<String, Object>> readShareSnapshot(ChatShare share) {
        if (share.getSnapshotJson() != null && !share.getSnapshotJson().isBlank()) {
            try {
                return objectMapper.readValue(share.getSnapshotJson(),
                        new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ignore) {
                return null;
            }
        }
        return extractChatMessages(share.getUserId(), share.getChatId());
    }

    /**
     * 复制消息并对文本内容中的常见 API Key、邮箱与手机号做不可逆脱敏。
     */
    private List<Map<String, Object>> sanitizeMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> source : messages) {
            Map<String, Object> item = new LinkedHashMap<>(source);
            if (item.get("content") instanceof String content) {
                String safe = API_KEY.matcher(content).replaceAll("[API_KEY已隐藏]");
                safe = EMAIL.matcher(safe).replaceAll("[邮箱已隐藏]");
                safe = PHONE.matcher(safe).replaceAll("[手机号已隐藏]");
                item.put("content", safe);
            }
            copy.add(item);
        }
        return copy;
    }

    /** 解析非负整数参数并限制上界。 */
    private int parseNonNegativeInt(Object value, int max) {
        if (value == null) return 0;
        try {
            return Math.max(0, Math.min(max, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 为匿名分享页中的受保护图片附加 shareId，只改响应副本，不污染快照。
     */
    private List<Map<String, Object>> withShareImageAccess(List<Map<String, Object>> messages, String shareId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : messages) {
            Map<String, Object> item = new LinkedHashMap<>(source);
            if (source.get("images") instanceof List<?> images) {
                List<Object> rewritten = new ArrayList<>();
                for (Object image : images) {
                    if (image instanceof String url && url.startsWith("/api/files/img/")) {
                        rewritten.add(url + (url.contains("?") ? "&" : "?") + "shareId=" + shareId);
                    } else {
                        rewritten.add(image);
                    }
                }
                item.put("images", rewritten);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 加载指定会话的消息列表（仅加载目标会话，不拉取全量历史）
     * @return 消息列表；会话不存在时为空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractChatMessages(String userId, String chatId) {
        Object msgs = chatHistoryService.loadSingleChat(userId, chatId).get("messages");
        return msgs instanceof List ? (List<Map<String, Object>>) msgs : null;
    }

    /**
     * 生成会话标题：取第一条用户消息前 20 字（与侧边栏标题规则一致）
     */
    private String buildTitle(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            if ("user".equals(m.get("role")) && m.get("content") instanceof String s && !s.isEmpty()) {
                return s.length() > 20 ? s.substring(0, 20) : s;
            }
        }
        return "分享的会话";
    }
}
