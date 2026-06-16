package com.chatai.newbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
 * 数据存储在 data/chat_history/ 目录下
 * 单个用户单个文件，文件超过20MB后归档并创建新文件
 */
@Service
public class ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20MB
    private final ObjectMapper objectMapper;
    private Path chatHistoryDir;

    // 每个用户独立的锁，保证文件操作的线程安全
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public ChatHistoryService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

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
     * 加载用户的会话历史（合并所有文件）
     * @param userId 用户ID
     * @return 会话数据 Map，包含 lastChatId 和 chats
     */
    public Map<String, Object> loadChatHistory(String userId) {
        Map<String, Object> mergedChats = new LinkedHashMap<>();
        String lastChatId = null;
        long latestUpdateTime = 0;

        // 查找该用户的所有会话文件
        File dir = chatHistoryDir.toFile();
        File[] files = dir.listFiles((d, name) ->
                name.equals(userId + ".json") ||
                (name.startsWith(userId + "_") && name.endsWith(".json")));

        if (files != null && files.length > 0) {
            // 按最后修改时间排序（从旧到新）
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));

            for (File file : files) {
                try {
                    Map<String, Object> data = objectMapper.readValue(file,
                            new TypeReference<Map<String, Object>>() {});

                    // 合并 chats
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fileChats = (Map<String, Object>) data.get("chats");
                    if (fileChats != null) {
                        mergedChats.putAll(fileChats);
                    }

                    // 处理已删除的会话ID
                    @SuppressWarnings("unchecked")
                    List<String> deletedIds = (List<String>) data.get("deletedChatIds");
                    if (deletedIds != null) {
                        for (String deletedId : deletedIds) {
                            mergedChats.remove(deletedId);
                        }
                    }

                    // 跟踪最新的 lastChatId
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
            chatData.put("updatedAt", LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            chatData.put("updatedAtTs", System.currentTimeMillis());

            File activeFile = chatHistoryDir.resolve(userId + ".json").toFile();

            // 检查当前文件是否超过20MB
            if (activeFile.exists() && activeFile.length() > MAX_FILE_SIZE) {
                // 归档当前文件
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

            // 写入活跃文件
            try {
                objectMapper.writeValue(activeFile, chatData);
            } catch (IOException e) {
                log.error("保存会话历史失败: userId={}", userId, e);
            }
        }
    }

    /**
     * 删除用户的所有会话历史
     */
    public void deleteChatHistory(String userId) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
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
