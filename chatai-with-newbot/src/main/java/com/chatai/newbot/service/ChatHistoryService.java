package com.chatai.newbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史持久化服务 - 实现多端会话同步
 * 支持两种存储模式（由 StorageManager.isUseSqlite() 控制）：
 * - JSON 模式：数据存储在 data/chat_history/ 目录下，单用户单文件
 * - SQLite 模式：按会话行存储在 t_chat_session 表（保存时仅重写变更会话，避免写放大），
 *   全局状态（lastChatId/deletedChatIds）存 t_chat_user_state 表；
 *   首次访问时从旧 t_chat_history 整文档懒迁移，旧文档保留作备份
 */
@Service
public class ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20MB
    private final ObjectMapper objectMapper;
    private Path chatHistoryDir;

    private final StorageManager storageManager;
    private final SqliteStorageService sqliteStorage;

    // 每个用户独立的锁，保证文件操作的线程安全
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public ChatHistoryService(StorageManager storageManager, SqliteStorageService sqliteStorage) {
        this.storageManager = storageManager;
        this.sqliteStorage = sqliteStorage;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 初始化：确保 JSON 存储目录存在（SQLite 模式下也需要，作为回退）
     */
    @PostConstruct
    public void init() {
        try {
            String userDir = System.getProperty("user.dir");
            this.chatHistoryDir = Paths.get(userDir, "data", "chat_history");
            Files.createDirectories(chatHistoryDir);
            log.info("会话历史存储目录: {}", chatHistoryDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("初始化会话历史存储目录失败", e);
        }
    }

    /**
     * 加载用户的会话历史（合并所有文件/数据库记录）
     * @param userId 用户ID
     * @return 会话数据 Map，包含 lastChatId 和 chats
     */
    public Map<String, Object> loadChatHistory(String userId) {
        if (storageManager.isUseSqlite()) {
            return loadChatHistoryFromSqlite(userId);
        }
        return loadChatHistoryFromFiles(userId);
    }

    /**
     * 从 SQLite 加载用户会话历史（由按会话行装配，导出/全文搜索等全量场景用）
     * @param userId 用户ID
     * @return 会话数据 Map
     */
    private Map<String, Object> loadChatHistoryFromSqlite(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        try {
            ensureSessionMigrated(userId);
            Map<String, Object> chats = new LinkedHashMap<>();
            Map<String, Object> chatMeta = new LinkedHashMap<>();
            for (Map<String, Object> row : sqliteStorage.listChatSessions(userId)) {
                String chatId = (String) row.get("chat_id");
                if (row.get("messages") instanceof String s && !s.isEmpty()) {
                    chats.put(chatId, objectMapper.readValue(s,
                            new TypeReference<List<Map<String, Object>>>() {}));
                }
                if (row.get("meta") instanceof String s && !s.isEmpty()) {
                    chatMeta.put(chatId, objectMapper.readValue(s,
                            new TypeReference<Map<String, Object>>() {}));
                }
            }
            Map<String, Object> state = sqliteStorage.loadChatUserState(userId);
            result.put("lastChatId", state != null ? state.get("last_chat_id") : null);
            result.put("chats", chats);
            result.put("chatMeta", chatMeta);
            result.put("deletedChatIds", parseDeletedIds(state));
            return result;
        } catch (Exception e) {
            log.error("从SQLite加载会话历史失败: userId={}", userId, e);
        }
        result.put("lastChatId", null);
        result.put("chats", new LinkedHashMap<>());
        return result;
    }

    /**
     * 确保用户的按会话行数据已就绪（幂等懒迁移）：
     * 以 t_chat_user_state 是否存在该用户行为守卫，首次访问时将旧 t_chat_history
     * 整文档拆分为 t_chat_session 行；无旧数据时也写入空状态行标记已迁移。
     * 旧整文档保留作备份，不删除。
     * @param userId 用户ID
     */
    @SuppressWarnings("unchecked")
    private void ensureSessionMigrated(String userId) {
        if (sqliteStorage.hasChatUserState(userId)) return;
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            if (sqliteStorage.hasChatUserState(userId)) return;
            String updatedAt = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long updatedAtTs = System.currentTimeMillis();
            String lastChatId = null;
            List<String> deletedIds = new ArrayList<>();
            int migrated = 0;
            try {
                String legacyJson = sqliteStorage.loadChatData(userId);
                if (legacyJson != null && !legacyJson.isEmpty()) {
                    Map<String, Object> legacy = objectMapper.readValue(legacyJson,
                            new TypeReference<Map<String, Object>>() {});
                    if (legacy.get("lastChatId") instanceof String s) {
                        lastChatId = s;
                    }
                    if (legacy.get("deletedChatIds") instanceof List<?> list) {
                        for (Object id : list) if (id instanceof String s) deletedIds.add(s);
                    }
                    Map<String, Object> legacyMeta = legacy.get("chatMeta") instanceof Map
                            ? (Map<String, Object>) legacy.get("chatMeta") : new LinkedHashMap<>();
                    if (legacy.get("chats") instanceof Map<?, ?> chats) {
                        for (Map.Entry<?, ?> entry : chats.entrySet()) {
                            if (!(entry.getValue() instanceof List)) continue;
                            String chatId = String.valueOf(entry.getKey());
                            List<Map<String, Object>> msgs = (List<Map<String, Object>>) entry.getValue();
                            Object meta = legacyMeta.get(chatId);
                            sqliteStorage.upsertChatSession(userId, chatId,
                                    objectMapper.writeValueAsString(msgs),
                                    meta != null ? objectMapper.writeValueAsString(meta) : null,
                                    buildChatTitle(msgs), buildChatPreview(msgs),
                                    findLastMessageTime(msgs), msgs.size(), updatedAt, updatedAtTs);
                            migrated++;
                        }
                    }
                }
                sqliteStorage.saveChatUserState(userId, lastChatId,
                        objectMapper.writeValueAsString(deletedIds), updatedAt, updatedAtTs);
                if (migrated > 0) {
                    log.info("会话历史已迁移为按会话行存储: userId={}, 会话数={}", userId, migrated);
                }
            } catch (Exception e) {
                log.error("会话历史按会话行迁移失败: userId={}", userId, e);
            }
        }
    }

    /**
     * 解析用户状态行中的 deleted_chat_ids JSON（空/解析失败返回空列表）
     */
    private List<String> parseDeletedIds(Map<String, Object> state) {
        if (state == null || !(state.get("deleted_chat_ids") instanceof String s) || s.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> ids = objectMapper.readValue(s, new TypeReference<List<String>>() {});
            return ids != null ? ids : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 从 JSON 文件加载用户会话历史（原有逻辑）
     * @param userId 用户ID
     * @return 会话数据 Map
     */
    private Map<String, Object> loadChatHistoryFromFiles(String userId) {
        Map<String, Object> mergedChats = new LinkedHashMap<>();
        Map<String, Object> mergedChatMeta = new LinkedHashMap<>();
        Set<String> mergedDeletedIds = new LinkedHashSet<>();
        String lastChatId = null;
        long latestUpdateTime = 0;

        File dir = chatHistoryDir.toFile();
        File[] files = dir.listFiles((d, name) ->
                name.equals(userId + ".json") ||
                (name.startsWith(userId + "_") && name.endsWith(".json")));

        if (files != null && files.length > 0) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File file : files) {
                try {
                    Map<String, Object> data = objectMapper.readValue(file,
                            new TypeReference<Map<String, Object>>() {});
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fileChats = (Map<String, Object>) data.get("chats");
                    if (fileChats != null) {
                        mergedChats.putAll(fileChats);
                    }
                    @SuppressWarnings("unchecked")
                    List<String> deletedIds = (List<String>) data.get("deletedChatIds");
                    if (deletedIds != null) {
                        for (String deletedId : deletedIds) {
                            mergedChats.remove(deletedId);
                            mergedChatMeta.remove(deletedId);
                            mergedDeletedIds.add(deletedId);
                        }
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fileChatMeta = (Map<String, Object>) data.get("chatMeta");
                    if (fileChatMeta != null) {
                        mergedChatMeta.putAll(fileChatMeta);
                    }
                    String fileLastChatId = (String) data.get("lastChatId");
                    Object updatedAtObj = data.get("updatedAtTs");
                    long fileUpdateTime = 0;
                    if (updatedAtObj instanceof Number) {
                        fileUpdateTime = ((Number) updatedAtObj).longValue();
                    }
                    if (fileUpdateTime >= latestUpdateTime) {
                        latestUpdateTime = fileUpdateTime;
                        lastChatId = fileLastChatId;
                    }
                } catch (Exception e) {
                    log.error("加载会话历史文件失败: {}", file.getName(), e);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("lastChatId", lastChatId);
        result.put("chats", mergedChats);
        result.put("chatMeta", mergedChatMeta);
        result.put("deletedChatIds", new ArrayList<>(mergedDeletedIds));
        return result;
    }

    /**
     * 加载用户会话摘要列表（懒加载模式下的首屏拉取）
     * 仅返回每个会话的标题/预览/最后时间/条数，不含消息内容，
     * 会话正文由前端切换会话时通过 loadSingleChat 按需加载
     * @param userId 用户ID
     * @return 包含 lastChatId、chatMeta、deletedChatIds 与 summaries 列表
     */
    public Map<String, Object> loadChatSummaries(String userId) {
        if (storageManager.isUseSqlite()) {
            return loadChatSummariesFromSqlite(userId);
        }
        Map<String, Object> history = loadChatHistory(userId);
        List<Map<String, Object>> summaries = new ArrayList<>();
        if (history.get("chats") instanceof Map<?, ?> chats) {
            for (Map.Entry<?, ?> entry : chats.entrySet()) {
                if (!(entry.getValue() instanceof List)) continue;
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> msgs = (List<Map<String, Object>>) entry.getValue();
                String preview = "";
                String lastTime = null;
                for (Map<String, Object> m : msgs) {
                    if (preview.isEmpty() && "user".equals(m.get("role"))
                            && m.get("content") instanceof String c && !c.isEmpty()) {
                        // 首条用户消息前 300 字，供侧边栏标题回退与模糊搜索
                        preview = c.length() > 300 ? c.substring(0, 300) : c;
                    }
                    if (m.get("time") instanceof String t && !t.isEmpty()) {
                        lastTime = t;
                    }
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", String.valueOf(entry.getKey()));
                summary.put("title", buildChatTitle(msgs));
                summary.put("preview", preview);
                summary.put("lastTime", lastTime);
                summary.put("count", msgs.size());
                summaries.add(summary);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastChatId", history.get("lastChatId"));
        result.put("chatMeta", history.get("chatMeta") != null ? history.get("chatMeta") : new LinkedHashMap<>());
        result.put("deletedChatIds", history.get("deletedChatIds") != null ? history.get("deletedChatIds") : new ArrayList<>());
        result.put("summaries", summaries);
        return result;
    }

    /**
     * 从 SQLite 按会话行直查摘要列表（仅读冗余摘要列，不解析消息 JSON）
     * @param userId 用户ID
     * @return 与 loadChatSummaries 相同结构的结果
     */
    private Map<String, Object> loadChatSummariesFromSqlite(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        Map<String, Object> chatMeta = new LinkedHashMap<>();
        Map<String, Object> state = null;
        try {
            ensureSessionMigrated(userId);
            for (Map<String, Object> row : sqliteStorage.listChatSessionSummaries(userId)) {
                String chatId = (String) row.get("chat_id");
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", chatId);
                summary.put("title", row.get("title") != null ? row.get("title") : "新会话");
                summary.put("preview", row.get("preview") != null ? row.get("preview") : "");
                summary.put("lastTime", row.get("last_time"));
                summary.put("count", row.get("msg_count") instanceof Number n ? n.intValue() : 0);
                summaries.add(summary);
                if (row.get("meta") instanceof String s && !s.isEmpty()) {
                    try {
                        chatMeta.put(chatId, objectMapper.readValue(s,
                                new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception ignore) {
                        // 单条元信息损坏不影响列表返回
                    }
                }
            }
            state = sqliteStorage.loadChatUserState(userId);
        } catch (Exception e) {
            log.error("从SQLite加载会话摘要失败: userId={}", userId, e);
        }
        result.put("lastChatId", state != null ? state.get("last_chat_id") : null);
        result.put("chatMeta", chatMeta);
        result.put("deletedChatIds", parseDeletedIds(state));
        result.put("summaries", summaries);
        return result;
    }

    /**
     * 加载单个会话的消息与元信息（发送消息前的当前会话快速同步）
     * 仅返回目标会话，避免多端场景下拉取全部历史造成的网络开销
     * @param userId 用户ID
     * @param chatId 会话ID
     * @return 包含 messages（消息列表）与 meta（会话元信息，可能为 null）
     */
    public Map<String, Object> loadSingleChat(String userId, String chatId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (storageManager.isUseSqlite()) {
            Object messages = null;
            Object meta = null;
            try {
                ensureSessionMigrated(userId);
                Map<String, Object> row = sqliteStorage.getChatSession(userId, chatId);
                if (row != null) {
                    if (row.get("messages") instanceof String s && !s.isEmpty()) {
                        messages = objectMapper.readValue(s,
                                new TypeReference<List<Map<String, Object>>>() {});
                    }
                    if (row.get("meta") instanceof String s && !s.isEmpty()) {
                        meta = objectMapper.readValue(s,
                                new TypeReference<Map<String, Object>>() {});
                    }
                }
            } catch (Exception e) {
                log.error("从SQLite加载单个会话失败: userId={}, chatId={}", userId, chatId, e);
            }
            result.put("messages", messages != null ? messages : new ArrayList<>());
            result.put("meta", meta);
            return result;
        }
        Map<String, Object> history = loadChatHistory(userId);
        Object messages = null;
        if (history.get("chats") instanceof Map<?, ?> chats) {
            messages = chats.get(chatId);
        }
        Object meta = null;
        if (history.get("chatMeta") instanceof Map<?, ?> chatMeta) {
            meta = chatMeta.get(chatId);
        }
        result.put("messages", messages != null ? messages : new ArrayList<>());
        result.put("meta", meta);
        return result;
    }

    /**
     * 保存用户的会话历史（增量合并语义）
     * 客户端可能只上传已加载的部分会话（懒加载模式），因此不做整体覆盖：
     * - SQLite 模式：仅 upsert 上传的会话行 + 删除 deletedChatIds 行，免整文档重写
     * - JSON 模式：以服务端已存数据为底，按会话 ID 覆盖上传的会话，再按 deletedChatIds 删除
     * @param userId 用户ID
     * @param chatData 会话数据，包含 lastChatId、chats、chatMeta、deletedChatIds
     */
    public void saveChatHistory(String userId, Map<String, Object> chatData) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            String updatedAt = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long updatedAtTs = System.currentTimeMillis();

            if (storageManager.isUseSqlite()) {
                saveChatHistoryToSqlite(userId, chatData, updatedAt, updatedAtTs);
            } else {
                mergeWithStored(userId, chatData);
                chatData.put("userId", userId);
                chatData.put("updatedAt", updatedAt);
                chatData.put("updatedAtTs", updatedAtTs);
                saveChatHistoryToFiles(userId, chatData);
            }
        }
    }

    /**
     * 将上传数据与服务端已存数据按会话合并（合并结果写回 chatData）：
     * - chats/chatMeta：已存数据为底，按会话 ID 用上传数据覆盖
     * - deletedChatIds：已存与上传累积合并后从 chats/chatMeta 中删除；
     *   若上传的 chats 中重新出现某已删 ID（JSON 备份导入恢复），则不再视为已删除
     * @param userId 用户ID
     * @param chatData 本次上传的会话数据（原地改写为合并结果）
     */
    @SuppressWarnings("unchecked")
    private void mergeWithStored(String userId, Map<String, Object> chatData) {
        Map<String, Object> stored = loadChatHistory(userId);

        Map<String, Object> incomingChats = chatData.get("chats") instanceof Map
                ? (Map<String, Object>) chatData.get("chats") : new LinkedHashMap<>();
        Map<String, Object> mergedChats = new LinkedHashMap<>();
        if (stored.get("chats") instanceof Map) {
            mergedChats.putAll((Map<String, Object>) stored.get("chats"));
        }
        mergedChats.putAll(incomingChats);

        Map<String, Object> mergedMeta = new LinkedHashMap<>();
        if (stored.get("chatMeta") instanceof Map) {
            mergedMeta.putAll((Map<String, Object>) stored.get("chatMeta"));
        }
        if (chatData.get("chatMeta") instanceof Map) {
            mergedMeta.putAll((Map<String, Object>) chatData.get("chatMeta"));
        }

        Set<String> deletedIds = new LinkedHashSet<>();
        if (stored.get("deletedChatIds") instanceof List<?> list) {
            for (Object id : list) if (id instanceof String s) deletedIds.add(s);
        }
        if (chatData.get("deletedChatIds") instanceof List<?> list) {
            for (Object id : list) if (id instanceof String s) deletedIds.add(s);
        }
        // 备份导入等场景会重新上传已删 ID 的会话，视为恢复
        deletedIds.removeAll(incomingChats.keySet());
        mergedChats.keySet().removeAll(deletedIds);
        mergedMeta.keySet().removeAll(deletedIds);

        chatData.put("chats", mergedChats);
        chatData.put("chatMeta", mergedMeta);
        chatData.put("deletedChatIds", new ArrayList<>(deletedIds));
    }

    /**
     * 保存会话历史到 SQLite（按会话行增量写入）：
     * 仅重写上传的会话行，未上传的会话保持原样，避免整文档重写的写放大。
     * deletedChatIds 与已存状态累积合并；若上传的 chats 中重新出现某已删 ID
     * （JSON 备份导入恢复），则不再视为已删除。
     * @param userId 用户ID
     * @param chatData 本次上传的会话数据
     * @param updatedAt 更新时间字符串
     * @param updatedAtTs 更新时间戳
     */
    @SuppressWarnings("unchecked")
    private void saveChatHistoryToSqlite(String userId, Map<String, Object> chatData,
                                          String updatedAt, long updatedAtTs) {
        try {
            ensureSessionMigrated(userId);
            Map<String, Object> incomingChats = chatData.get("chats") instanceof Map
                    ? (Map<String, Object>) chatData.get("chats") : new LinkedHashMap<>();
            Map<String, Object> incomingMeta = chatData.get("chatMeta") instanceof Map
                    ? (Map<String, Object>) chatData.get("chatMeta") : new LinkedHashMap<>();

            Map<String, Object> state = sqliteStorage.loadChatUserState(userId);
            Set<String> deletedIds = new LinkedHashSet<>(parseDeletedIds(state));
            if (chatData.get("deletedChatIds") instanceof List<?> list) {
                for (Object id : list) if (id instanceof String s) deletedIds.add(s);
            }
            // 备份导入等场景会重新上传已删 ID 的会话，视为恢复
            deletedIds.removeAll(incomingChats.keySet());

            for (Map.Entry<String, Object> entry : incomingChats.entrySet()) {
                if (!(entry.getValue() instanceof List)) continue;
                String chatId = entry.getKey();
                List<Map<String, Object>> msgs = (List<Map<String, Object>>) entry.getValue();
                Object meta = incomingMeta.get(chatId);
                sqliteStorage.upsertChatSession(userId, chatId,
                        objectMapper.writeValueAsString(msgs),
                        meta != null ? objectMapper.writeValueAsString(meta) : null,
                        buildChatTitle(msgs), buildChatPreview(msgs),
                        findLastMessageTime(msgs), msgs.size(), updatedAt, updatedAtTs);
            }
            sqliteStorage.deleteChatSessions(userId, deletedIds);

            String lastChatId = null;
            if (chatData.get("lastChatId") instanceof String s && !s.isEmpty()) {
                lastChatId = s;
            } else if (state != null && state.get("last_chat_id") instanceof String s) {
                lastChatId = s;
            }
            sqliteStorage.saveChatUserState(userId, lastChatId,
                    objectMapper.writeValueAsString(new ArrayList<>(deletedIds)), updatedAt, updatedAtTs);
        } catch (Exception e) {
            log.error("保存会话历史到SQLite失败: userId={}", userId, e);
        }
    }

    /**
     * 保存会话历史到 JSON 文件（原有逻辑，含20MB归档）
     * @param userId 用户ID
     * @param chatData 会话数据
     */
    private void saveChatHistoryToFiles(String userId, Map<String, Object> chatData) {
        File activeFile = chatHistoryDir.resolve(userId + ".json").toFile();

        // 检查当前文件是否超过20MB
        if (activeFile.exists() && activeFile.length() > MAX_FILE_SIZE) {
            String timestamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File archiveFile = chatHistoryDir.resolve(
                    userId + "_" + timestamp + ".json").toFile();
            if (activeFile.renameTo(archiveFile)) {
                log.info("会话历史文件超过20MB，已归档: {} -> {}",
                        activeFile.getName(), archiveFile.getName());
            } else {
                log.warn("归档会话历史文件失败: {}", activeFile.getName());
            }
        }

        try {
            objectMapper.writeValue(activeFile, chatData);
        } catch (IOException e) {
            log.error("保存会话历史失败: userId={}", userId, e);
        }
    }

    /**
     * 跨会话全文搜索：在用户所有会话的消息内容中检索关键字（忽略大小写）
     * @param userId 用户ID
     * @param keyword 搜索关键字
     * @param limit 最大返回条数
     * @return 匹配列表，每项含 chatId/chatTitle/role/time/snippet
     */
    public List<Map<String, Object>> searchChatHistory(String userId, String keyword, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return results;
        }
        String kw = keyword.trim().toLowerCase();
        Map<String, Object> history = loadChatHistory(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> chats = (Map<String, Object>) history.get("chats");
        if (chats == null) {
            return results;
        }
        for (Map.Entry<String, Object> entry : chats.entrySet()) {
            if (!(entry.getValue() instanceof List)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) entry.getValue();
            String chatTitle = buildChatTitle(msgs);
            for (Map<String, Object> msg : msgs) {
                if (!(msg.get("content") instanceof String content)) continue;
                int pos = content.toLowerCase().indexOf(kw);
                if (pos < 0) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chatId", entry.getKey());
                item.put("chatTitle", chatTitle);
                item.put("role", msg.get("role"));
                item.put("time", msg.get("time"));
                item.put("snippet", buildSnippet(content, pos, kw.length()));
                results.add(item);
                if (results.size() >= limit) {
                    return results;
                }
            }
        }
        return results;
    }

    /**
     * 生成会话标题：取第一条用户消息前 20 字（与前端侧边栏标题规则一致）
     */
    private String buildChatTitle(List<Map<String, Object>> msgs) {
        for (Map<String, Object> m : msgs) {
            if ("user".equals(m.get("role")) && m.get("content") instanceof String s && !s.isEmpty()) {
                return s.length() > 20 ? s.substring(0, 20) : s;
            }
        }
        return "新会话";
    }

    /**
     * 生成会话预览：首条用户消息前 300 字，供侧边栏标题回退与模糊搜索
     */
    private String buildChatPreview(List<Map<String, Object>> msgs) {
        for (Map<String, Object> m : msgs) {
            if ("user".equals(m.get("role")) && m.get("content") instanceof String c && !c.isEmpty()) {
                return c.length() > 300 ? c.substring(0, 300) : c;
            }
        }
        return "";
    }

    /**
     * 取会话中最后一条带时间的消息时间（无则返回 null）
     */
    private String findLastMessageTime(List<Map<String, Object>> msgs) {
        String lastTime = null;
        for (Map<String, Object> m : msgs) {
            if (m.get("time") instanceof String t && !t.isEmpty()) {
                lastTime = t;
            }
        }
        return lastTime;
    }

    /**
     * 生成匹配片段：关键字前后各保留约 40 字，压缩空白字符
     */
    private String buildSnippet(String content, int pos, int kwLen) {
        int start = Math.max(0, pos - 40);
        int end = Math.min(content.length(), pos + kwLen + 40);
        String snippet = content.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + snippet + (end < content.length() ? "…" : "");
    }

    /**
     * 删除用户的所有会话历史
     * @param userId 用户ID
     */
    public void deleteChatHistory(String userId) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            if (storageManager.isUseSqlite()) {
                sqliteStorage.deleteChatData(userId);
                log.info("已从SQLite删除会话历史: userId={}", userId);
            }
            // 无论哪种模式，都清理 JSON 文件（确保切换后无残留）
            File dir = chatHistoryDir.toFile();
            File[] files = dir.listFiles((d, name) ->
                    name.equals(userId + ".json") ||
                    (name.startsWith(userId + "_") && name.endsWith(".json")));
            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        log.info("已删除会话历史文件: {}", file.getName());
                    }
                }
            }
        }
    }
}
