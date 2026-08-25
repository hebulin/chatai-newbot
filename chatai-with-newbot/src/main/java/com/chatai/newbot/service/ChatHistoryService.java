package com.chatai.newbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话历史持久化服务 - 实现多端会话同步
 * 数据按会话行存储在 SQLite t_chat_session 表（保存时仅重写变更会话，避免写放大），
 * 全局状态（lastChatId/deletedChatIds）存 t_chat_user_state 表；
 * 首次访问时从旧 t_chat_history 整文档懒迁移，旧文档保留作备份
 */
@Service
public class ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private final ObjectMapper objectMapper;

    private final SqliteStorageService sqliteStorage;

    // 每个用户独立的锁，保证并发读写的线程安全
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public ChatHistoryService(SqliteStorageService sqliteStorage) {
        this.sqliteStorage = sqliteStorage;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 加载用户的会话历史（全量装配，导出/全文搜索等场景用）
     * @param userId 用户ID
     * @return 会话数据 Map，包含 lastChatId 和 chats
     */
    public Map<String, Object> loadChatHistory(String userId) {
        return loadChatHistoryFromSqlite(userId);
    }

    /**
     * 过滤出用户名下仍存在的会话ID集合（分享状态判定等轻量场景用，
     * SQLite 模式下仅查会话ID列，不加载消息内容）
     * @param userId 用户ID
     * @param chatIds 待检查的会话ID集合
     * @return 实际存在的会话ID集合
     */
    public Set<String> filterExistingChats(String userId, Collection<String> chatIds) {
        Set<String> existing = new HashSet<>();
        if (chatIds == null || chatIds.isEmpty()) return existing;
        try {
            ensureSessionMigrated(userId);
            Set<String> all = new HashSet<>(sqliteStorage.listChatSessionIds(userId));
            for (String id : chatIds) {
                if (all.contains(id)) existing.add(id);
            }
        } catch (Exception e) {
            log.error("检查会话存在性失败: userId={}", userId, e);
        }
        return existing;
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
            result.put("folders", parseFolders(state));
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
                        objectMapper.writeValueAsString(deletedIds), null, updatedAt, updatedAtTs);
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
     * 解析用户状态行中的 folders_json（会话文件夹定义列表，空/解析失败返回空列表）。
     * 每项为 { id, name, collapsed } 的 Map，会话与文件夹的归属关系另存于会话 meta.folderId。
     */
    private List<Object> parseFolders(Map<String, Object> state) {
        if (state == null || !(state.get("folders_json") instanceof String s) || s.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Object> folders = objectMapper.readValue(s, new TypeReference<List<Object>>() {});
            return folders != null ? folders : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 查询用户会话数据的当前版本号（多端自动同步的轻量变更检测）
     * @param userId 用户ID
     * @return 版本号（会话行与状态行 updated_at_ts 最大值）
     */
    public long getChatHistoryVersion(String userId) {
        try {
            ensureSessionMigrated(userId);
            return sqliteStorage.getChatHistoryVersion(userId);
        } catch (Exception e) {
            log.error("查询会话版本号失败: userId={}", userId, e);
            return 0L;
        }
    }

    /**
     * 加载用户会话摘要列表（懒加载模式下的首屏拉取）
     * 仅返回每个会话的标题/预览/最后时间/条数，不含消息内容，
     * 会话正文由前端切换会话时通过 loadSingleChat 按需加载
     * @param userId 用户ID
     * @return 包含 lastChatId、chatMeta、deletedChatIds 与 summaries 列表
     */
    public Map<String, Object> loadChatSummaries(String userId) {
        return loadChatSummariesFromSqlite(userId);
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
        long version = 0L;
        try {
            ensureSessionMigrated(userId);
            // 版本号先于摘要读取：若读取期间有并发写入，下次变更检测仍能发现新版本
            version = sqliteStorage.getChatHistoryVersion(userId);
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
        result.put("folders", parseFolders(state));
        // 随摘要一并返回当前版本号，前端以此作为后续变更检测的基准
        result.put("version", version);
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

    /**
     * 保存用户的会话历史（增量合并语义）
     * 客户端可能只上传已加载的部分会话（懒加载模式），因此不做整体覆盖：
     * 仅 upsert 上传的会话行 + 删除 deletedChatIds 行，免整文档重写。
     * 多步写操作（批量 upsert + 批量删除 + 状态保存）由事务包裹，
     * 进程崩溃时整体回滚，避免"会话已保存但删除列表未更新"的数据不一致。
     * @param userId 用户ID
     * @param chatData 会话数据，包含 lastChatId、chats、chatMeta、deletedChatIds
     * @return 本次保存后的版本号（前端据此更新本地基准，避免自己的写入触发重拉）
     */
    @Transactional
    public long saveChatHistory(String userId, Map<String, Object> chatData) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            String updatedAt = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long updatedAtTs = System.currentTimeMillis();
            saveChatHistoryToSqlite(userId, chatData, updatedAt, updatedAtTs);
            return updatedAtTs;
        }
    }

    /**
     * 保存会话历史到 SQLite（按会话行增量写入）：
     * 仅重写上传的会话行，未上传的会话保持原样，避免整文档重写的写放大。
     * deletedChatIds 与已存状态累积合并；若上传的 chats 中重新出现某已删 ID
     * （JSON 备份导入恢复），则不再视为已删除。
     * 任一步骤失败抛出异常，由外层 saveChatHistory 的 @Transactional 统一回滚，
     * 并交由控制器层返回失败提示（此前静默吞掉会让前端误以为保存成功）。
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
            // 文件夹定义：仅在客户端显式上传 folders 字段时覆盖，旧客户端不带该字段则保留服务端现有值
            String foldersJson = state != null && state.get("folders_json") instanceof String exist ? exist : null;
            if (chatData.get("folders") instanceof List<?> folderList) {
                foldersJson = objectMapper.writeValueAsString(folderList);
            }
            sqliteStorage.saveChatUserState(userId, lastChatId,
                    objectMapper.writeValueAsString(new ArrayList<>(deletedIds)), foldersJson, updatedAt, updatedAtTs);
        } catch (Exception e) {
            log.error("保存会话历史到SQLite失败: userId={}", userId, e);
            // 抛出运行时异常触发事务回滚，控制器层 catch 后返回失败提示
            throw new IllegalStateException("保存会话历史失败", e);
        }
    }

    /** 全文搜索单次最多扫描的候选会话数（SQL 粗筛上限，防止重度用户全表解析消息 JSON） */
    private static final int SEARCH_SESSION_SCAN_LIMIT = 200;

    /**
     * 跨会话全文搜索：先用 SQL LIKE 在 t_chat_session 标题与消息正文列粗筛候选会话
     * （避免全量加载用户所有会话到内存），再对候选会话的标题与消息内容做忽略大小写的精确匹配。
     * LIKE 命中范围包含标题与消息 JSON 全文（消息 JSON 可能误命中字段名等非内容文本），
     * 以 Java 侧标题或 content 精确匹配为准；候选会话按最近更新时间倒序，新会话的匹配优先返回。
     * @param userId 用户ID
     * @param keyword 搜索关键字
     * @param limit 最大返回条数
     * @return 匹配列表；标题项含 resultType=title，消息项额外含 role/time/messageIndex/snippet
     */
    public List<Map<String, Object>> searchChatHistory(String userId, String keyword, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return results;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        try {
            ensureSessionMigrated(userId);
            List<Map<String, Object>> candidates =
                    sqliteStorage.searchChatSessionsByKeyword(userId, keyword.trim(), SEARCH_SESSION_SCAN_LIMIT);
            for (Map<String, Object> row : candidates) {
                if (results.size() >= limit) break;
                String chatId = (String) row.get("chat_id");
                List<Map<String, Object>> msgs = Collections.emptyList();
                if (row.get("messages") instanceof String messagesJson && !messagesJson.isEmpty()) {
                    try {
                        msgs = objectMapper.readValue(messagesJson,
                                new TypeReference<List<Map<String, Object>>>() {});
                    } catch (Exception e) {
                        // 单条会话消息损坏时仍允许按标题命中
                    }
                }
                String chatTitle = row.get("title") instanceof String t && !t.isEmpty()
                        ? t : buildChatTitle(msgs);
                if (chatTitle.toLowerCase(Locale.ROOT).contains(kw)) {
                    Map<String, Object> titleItem = new LinkedHashMap<>();
                    titleItem.put("resultType", "title");
                    titleItem.put("chatId", chatId);
                    titleItem.put("chatTitle", chatTitle);
                    titleItem.put("snippet", chatTitle);
                    results.add(titleItem);
                }
                for (int messageIndex = 0; messageIndex < msgs.size() && results.size() < limit; messageIndex++) {
                    Map<String, Object> msg = msgs.get(messageIndex);
                    if (!(msg.get("content") instanceof String content)) continue;
                    int pos = content.toLowerCase(Locale.ROOT).indexOf(kw);
                    if (pos < 0) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resultType", "message");
                    item.put("chatId", chatId);
                    item.put("chatTitle", chatTitle);
                    item.put("messageIndex", messageIndex);
                    item.put("role", msg.get("role"));
                    item.put("time", msg.get("time"));
                    item.put("snippet", buildSnippet(content, pos, kw.length()));
                    results.add(item);
                }
            }
        } catch (Exception e) {
            log.error("会话全文搜索失败: userId={}, keyword={}", userId, keyword, e);
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
     * 删除用户的所有会话历史（三张表清理由事务包裹，保证原子性）
     * @param userId 用户ID
     */
    @Transactional
    public void deleteChatHistory(String userId) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            sqliteStorage.deleteChatData(userId);
            log.info("已从SQLite删除会话历史: userId={}", userId);
        }
    }
}
