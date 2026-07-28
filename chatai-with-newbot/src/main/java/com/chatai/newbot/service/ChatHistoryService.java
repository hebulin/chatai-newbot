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
 * - SQLite 模式：数据存储在 t_chat_history 表中，整条 JSON 文档存 TEXT 字段
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
     * 从 SQLite 加载用户会话历史
     * @param userId 用户ID
     * @return 会话数据 Map
     */
    private Map<String, Object> loadChatHistoryFromSqlite(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        try {
            String chatDataJson = sqliteStorage.loadChatData(userId);
            if (chatDataJson != null && !chatDataJson.isEmpty()) {
                Map<String, Object> data = objectMapper.readValue(chatDataJson,
                        new TypeReference<Map<String, Object>>() {});
                result.put("lastChatId", data.get("lastChatId"));
                result.put("chats", data.get("chats"));
                result.put("chatMeta", data.get("chatMeta"));
                return result;
            }
        } catch (Exception e) {
            log.error("从SQLite加载会话历史失败: userId={}", userId, e);
        }
        result.put("lastChatId", null);
        result.put("chats", new LinkedHashMap<>());
        return result;
    }

    /**
     * 从 JSON 文件加载用户会话历史（原有逻辑）
     * @param userId 用户ID
     * @return 会话数据 Map
     */
    private Map<String, Object> loadChatHistoryFromFiles(String userId) {
        Map<String, Object> mergedChats = new LinkedHashMap<>();
        Map<String, Object> mergedChatMeta = new LinkedHashMap<>();
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
     * 保存用户的会话历史
     * @param userId 用户ID
     * @param chatData 会话数据，包含 lastChatId、chats、deletedChatIds
     */
    public void saveChatHistory(String userId, Map<String, Object> chatData) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            chatData.put("userId", userId);
            String updatedAt = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long updatedAtTs = System.currentTimeMillis();
            chatData.put("updatedAt", updatedAt);
            chatData.put("updatedAtTs", updatedAtTs);

            if (storageManager.isUseSqlite()) {
                saveChatHistoryToSqlite(userId, chatData, updatedAt, updatedAtTs);
            } else {
                saveChatHistoryToFiles(userId, chatData);
            }
        }
    }

    /**
     * 保存会话历史到 SQLite（整条 JSON 文档存 TEXT 字段）
     * @param userId 用户ID
     * @param chatData 会话数据
     * @param updatedAt 更新时间字符串
     * @param updatedAtTs 更新时间戳
     */
    private void saveChatHistoryToSqlite(String userId, Map<String, Object> chatData,
                                          String updatedAt, long updatedAtTs) {
        try {
            String json = objectMapper.writeValueAsString(chatData);
            sqliteStorage.saveChatData(userId, json, updatedAt, updatedAtTs);
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
