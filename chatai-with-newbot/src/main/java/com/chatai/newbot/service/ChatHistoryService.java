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
                        }
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
