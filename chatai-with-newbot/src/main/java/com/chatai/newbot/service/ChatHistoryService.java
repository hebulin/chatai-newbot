package com.chatai.newbot.service;

import com.chatai.newbot.exception.ChatSyncConflictException;
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
                                    ChatPayloadUtils.buildChatTitle(msgs), ChatPayloadUtils.buildChatPreview(msgs),
                                    ChatPayloadUtils.findLastMessageTime(msgs), msgs.size(), updatedAt, updatedAtTs);
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
                // 会话行版本号：多端同步的乐观锁基准与变更检测依据（替代原先的消息条数+时间比对）
                summary.put("version", row.get("version") instanceof Number n ? n.longValue() : 0L);
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
        // 文件夹定义版本号：文件夹并发修改的乐观锁基准
        result.put("foldersVersion", state != null && state.get("folders_version") instanceof Number n
                ? n.longValue() : 0L);
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
        long version = 0L;
        boolean exists = false;
        try {
            ensureSessionMigrated(userId);
            Map<String, Object> row = sqliteStorage.getChatSession(userId, chatId);
            if (row != null) {
                exists = true;
                if (row.get("messages") instanceof String s && !s.isEmpty()) {
                    messages = objectMapper.readValue(s,
                            new TypeReference<List<Map<String, Object>>>() {});
                }
                if (row.get("meta") instanceof String s && !s.isEmpty()) {
                    meta = objectMapper.readValue(s,
                            new TypeReference<Map<String, Object>>() {});
                }
                if (row.get("version") instanceof Number n) {
                    version = n.longValue();
                }
            }
        } catch (Exception e) {
            log.error("从SQLite加载单个会话失败: userId={}, chatId={}", userId, chatId, e);
        }
        result.put("messages", messages != null ? messages : new ArrayList<>());
        result.put("meta", meta);
        // 会话行版本号（乐观锁基准）与存在性（区分"不存在"与"空会话"）
        result.put("version", version);
        result.put("exists", exists);
        return result;
    }

    /**
     * 保存用户的会话历史（增量合并 + 服务端原子版本校验）
     * 客户端仅上传实际变化的会话/元信息/文件夹；每个会话携带基准版本号（baseVersions），
     * 服务端逐个做乐观锁校验：版本不一致或会话已被其他端删除时不覆盖、返回明确冲突项，
     * 未冲突的会话正常写入；删除仅按 deletedChatIds 累积执行，上传已删会话不再自动复活，
     * 恢复必须经 restoreChatIds 显式声明。
     * 全部写操作由事务包裹，进程崩溃时整体回滚。
     * @param userId 用户ID
     * @param chatData 会话数据：lastChatId、chats（仅变更会话）、chatMeta（仅变更元信息）、
     *                 deletedChatIds、folders（可选）、baseVersions（每会话基准版本）、
     *                 baseFoldersVersion（文件夹基准版本）、restoreChatIds（显式恢复列表）
     * @return 保存结果：version（新同步序列号）、versions（每会话新版本）、
     *         conflicts（冲突明细）、foldersVersion（文件夹新版本）
     */
    @Transactional
    public Map<String, Object> saveChatHistory(String userId, Map<String, Object> chatData) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            String updatedAt = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            long updatedAtTs = System.currentTimeMillis();
            Map<String, Object> result = saveChatHistoryToSqlite(userId, chatData, updatedAt, updatedAtTs);
            // 全局同步序列号：任何一次保存请求（含纯冲突未写入）都推进版本，
            // 使其他端能感知到删除列表/状态变化；由服务端原子自增，不依赖客户端时钟
            long newSeq = sqliteStorage.bumpChatSyncSeq(userId);
            result.put("version", newSeq);
            return result;
        }
    }

    /**
     * 保存会话历史到 SQLite（按会话行增量写入 + 乐观版本校验）：
     * 仅重写上传的变更会话，未上传的会话保持原样。
     * deletedChatIds 与已存状态累积合并；上传已删除的会话不自动复活（返回 deleted 冲突），
     * 仅 restoreChatIds 显式声明的会话允许恢复写入。
     * 元信息支持独立更新（不带消息正文），用于置顶/重命名/文件夹归属等轻量变更。
     * 任一步骤失败抛出异常，由外层 saveChatHistory 的 @Transactional 统一回滚。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> saveChatHistoryToSqlite(String userId, Map<String, Object> chatData,
                                           String updatedAt, long updatedAtTs) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> newVersions = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        result.put("versions", newVersions);
        result.put("conflicts", conflicts);
        try {
            ensureSessionMigrated(userId);
            Map<String, Object> incomingChats = chatData.get("chats") instanceof Map
                    ? (Map<String, Object>) chatData.get("chats") : new LinkedHashMap<>();
            Map<String, Object> incomingMeta = chatData.get("chatMeta") instanceof Map
                    ? (Map<String, Object>) chatData.get("chatMeta") : new LinkedHashMap<>();
            Map<String, Object> baseVersions = chatData.get("baseVersions") instanceof Map
                    ? (Map<String, Object>) chatData.get("baseVersions") : null;
            Set<String> restoreIds = new LinkedHashSet<>();
            if (chatData.get("restoreChatIds") instanceof List<?> list) {
                for (Object id : list) if (id instanceof String s) restoreIds.add(s);
            }

            Map<String, Object> state = sqliteStorage.loadChatUserState(userId);
            Set<String> deletedIds = new LinkedHashSet<>(parseDeletedIds(state));
            if (chatData.get("deletedChatIds") instanceof List<?> list) {
                for (Object id : list) if (id instanceof String s) deletedIds.add(s);
            }
            // 显式恢复的会话从删除集合中移除；不再按“上传即复活”处理
            deletedIds.removeAll(restoreIds);

            // 1) 会话内容写入（带版本校验与删除保护）
            for (Map.Entry<String, Object> entry : incomingChats.entrySet()) {
                if (!(entry.getValue() instanceof List)) continue;
                String chatId = entry.getKey();
                List<Map<String, Object>> msgs = (List<Map<String, Object>>) entry.getValue();
                // 删除保护：状态表删除列表或回收站软删除标记命中，且未显式恢复时拒绝写入
                boolean markedDeleted = deletedIds.contains(chatId)
                        || sqliteStorage.getChatSessionDeletedAt(userId, chatId) != null;
                if (markedDeleted && !restoreIds.contains(chatId)) {
                    // 陈旧客户端上传已删会话：拒绝写入并明确告知，绝不静默复活
                    conflicts.add(conflictItem(chatId, "deleted", 0));
                    continue;
                }
                Long expected = resolveExpectedVersion(baseVersions, chatId, restoreIds.contains(chatId));
                Object meta = incomingMeta.get(chatId);
                try {
                    long newVersion = sqliteStorage.upsertChatSessionChecked(userId, chatId,
                            objectMapper.writeValueAsString(msgs),
                            meta != null ? objectMapper.writeValueAsString(meta) : null,
                            ChatPayloadUtils.buildChatTitle(msgs), ChatPayloadUtils.buildChatPreview(msgs),
                            ChatPayloadUtils.findLastMessageTime(msgs), msgs.size(), updatedAt, updatedAtTs, expected);
                    newVersions.put(chatId, newVersion);
                } catch (ChatSyncConflictException e) {
                    conflicts.add(conflictItem(chatId, "version", e.getServerVersion()));
                }
            }

            // 2) 仅元信息变更（chatMeta 中存在但 chats 未上传的会话）
            for (Map.Entry<String, Object> entry : incomingMeta.entrySet()) {
                String chatId = entry.getKey();
                if (incomingChats.containsKey(chatId)) continue;
                if (deletedIds.contains(chatId) && !restoreIds.contains(chatId)) {
                    conflicts.add(conflictItem(chatId, "deleted", 0));
                    continue;
                }
                Long expected = resolveExpectedVersion(baseVersions, chatId, false);
                try {
                    boolean updated = sqliteStorage.updateChatSessionMetaChecked(userId, chatId,
                            objectMapper.writeValueAsString(entry.getValue()),
                            expected, updatedAt, updatedAtTs);
                    if (updated) {
                        Integer v = sqliteStorage.getChatSessionVersion(userId, chatId);
                        if (v != null) newVersions.put(chatId, v.longValue());
                    }
                } catch (ChatSyncConflictException e) {
                    conflicts.add(conflictItem(chatId, "version", e.getServerVersion()));
                }
            }

            // 3) 删除列表落库（软删除进回收站，行保留可恢复；彻底删除走回收站接口）
            sqliteStorage.softDeleteChatSessions(userId, deletedIds, updatedAt);

            // 4) 全局状态：lastChatId + 删除列表 + 文件夹定义（带独立版本校验）
            String lastChatId = null;
            if (chatData.get("lastChatId") instanceof String s && !s.isEmpty()) {
                lastChatId = s;
            } else if (state != null && state.get("last_chat_id") instanceof String s) {
                lastChatId = s;
            }
            String foldersJson = state != null && state.get("folders_json") instanceof String exist ? exist : null;
            long foldersVersion = state != null && state.get("folders_version") instanceof Number n
                    ? n.longValue() : 0L;
            if (chatData.get("folders") instanceof List<?> folderList) {
                Long baseFv = chatData.get("baseFoldersVersion") instanceof Number n ? n.longValue() : null;
                if (baseFv != null && baseFv != foldersVersion) {
                    // 文件夹定义在其他端已变更：不覆盖，返回冲突由前端合并后重试
                    conflicts.add(conflictItem("__folders__", "folders", foldersVersion));
                } else {
                    foldersJson = objectMapper.writeValueAsString(folderList);
                    sqliteStorage.saveChatUserState(userId, lastChatId,
                            objectMapper.writeValueAsString(new ArrayList<>(deletedIds)), foldersJson, updatedAt, updatedAtTs);
                    foldersVersion = sqliteStorage.bumpChatFoldersVersion(userId);
                    // saveChatUserState 已写状态行，避免下方重复写
                    result.put("foldersVersion", foldersVersion);
                    return result;
                }
            }
            sqliteStorage.saveChatUserState(userId, lastChatId,
                    objectMapper.writeValueAsString(new ArrayList<>(deletedIds)), foldersJson, updatedAt, updatedAtTs);
            result.put("foldersVersion", foldersVersion);
            return result;
        } catch (Exception e) {
            log.error("保存会话历史到SQLite失败: userId={}", userId, e);
            // 抛出运行时异常触发事务回滚，控制器层 catch 后返回失败提示
            throw new IllegalStateException("保存会话历史失败", e);
        }
    }

    /**
     * 解析客户端为某会话提交的基准版本号：
     * 显式恢复（restoreChatIds）的会话绕过版本校验（删除后行可能不存在）；
     * baseVersions 缺失表示旧客户端，返回 null 走兼容直写。
     */
    private Long resolveExpectedVersion(Map<String, Object> baseVersions, String chatId, boolean restoring) {
        if (restoring) return null;
        if (baseVersions == null) return null;
        Object v = baseVersions.get(chatId);
        if (v instanceof Number n) return n.longValue();
        return 0L; // 显式声明了基准版本但缺该会话：视为新会话
    }

    /** 组装一条冲突明细（chatId="__folders__" 表示文件夹定义冲突） */
    private Map<String, Object> conflictItem(String chatId, String reason, long serverVersion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chatId", chatId);
        item.put("reason", reason);
        item.put("serverVersion", serverVersion);
        return item;
    }

    /** 全文搜索每批 SQL 粗筛的候选会话数（分页扫描直至结果集满或候选穷尽，不设全局上限截断旧结果） */
    private static final int SEARCH_BATCH_SIZE = 200;

    /**
     * 跨会话全文搜索（分页扫描 + 字面量匹配 + 筛选）：
     * SQL LIKE 粗筛按 updated_at_ts DESC + chat_id 稳定排序分批拉取候选，
     * Java 侧对标题与消息 content 做忽略大小写的精确子串匹配（中文/英文大小写/连续子串均支持），
     * 直到结果集达到 limit+1（用于判定 hasMore）或候选扫描完毕，不会因固定候选上限遗漏旧会话。
     * 模型筛选在精确匹配阶段按消息 modelName 等值过滤。
     * @param userId 用户ID
     * @param keyword 搜索关键字（字面量）
     * @param limit 最大返回条数
     * @param offset 结果偏移量（稳定分页）
     * @param fromTs 起始时间戳（毫秒，可空，按会话更新时间）
     * @param toTs 结束时间戳（毫秒，可空）
     * @param folderId 文件夹归属筛选（可空）
     * @param modelName 模型名称筛选（可空，精确匹配消息的 modelName）
     * @return { results: [...], hasMore: boolean }；消息命中项含 role/time/messageIndex
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchChatHistory(String userId, String keyword, int limit, int offset,
                                                  Long fromTs, Long toTs, String folderId, String modelName) {
        List<Map<String, Object>> matched = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", matched);
        out.put("hasMore", false);
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        long startNanos = System.nanoTime();
        int scannedSessions = 0;
        try {
            ensureSessionMigrated(userId);
            // 分批扫描候选会话，直至收集到 offset+limit+1 条结果（多取一条判定 hasMore）或候选穷尽
            int need = offset + limit + 1;
            int batchOffset = 0;
            while (matched.size() < need) {
                List<Map<String, Object>> batch = sqliteStorage.searchChatSessionsByKeyword(
                        userId, keyword.trim(), SEARCH_BATCH_SIZE, batchOffset, fromTs, toTs, folderId);
                if (batch.isEmpty()) break;
                scannedSessions += batch.size();
                batchOffset += batch.size();
                for (Map<String, Object> row : batch) {
                    if (matched.size() >= need) break;
                    collectSearchMatches(row, kw, modelName, matched);
                }
                if (batch.size() < SEARCH_BATCH_SIZE) break; // 候选已穷尽
            }
        } catch (Exception e) {
            log.error("会话全文搜索失败: userId={}, keyword={}", userId, keyword, e);
        }
        // 分页切片：offset 之后取 limit 条；多取的一条用于 hasMore 判定
        boolean hasMore = matched.size() > offset + limit;
        List<Map<String, Object>> page = matched.size() <= offset ? new ArrayList<>()
                : new ArrayList<>(matched.subList(offset, Math.min(matched.size(), offset + limit)));
        out.put("results", page);
        out.put("hasMore", hasMore);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("会话搜索: userId={}, keywordLen={}, 扫描会话数={}, 命中={}, 耗时={}ms",
                userId, kw.length(), scannedSessions, matched.size(), elapsedMs);
        return out;
    }

    /**
     * 对单个候选会话做精确匹配并收集命中项（标题命中 + 消息 content 命中）。
     * 标题命中范围包含服务端标题与用户自定义标题（meta.title 由调用方摘要链路覆盖，
     * 此处以行标题为准）；消息 JSON 字段名等非内容文本不会被判为命中。
     */
    private void collectSearchMatches(Map<String, Object> row, String kw, String modelName,
                                       List<Map<String, Object>> matched) {
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
                ? t : ChatPayloadUtils.buildChatTitle(msgs);
        // 用户重命名标题（meta.title）也纳入标题命中范围
        String customTitle = null;
        if (row.get("meta") instanceof String metaJson && !metaJson.isEmpty()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
                if (meta.get("title") instanceof String ct && !ct.isEmpty()) customTitle = ct;
            } catch (Exception ignore) { /* meta 损坏忽略 */ }
        }
        boolean titleHit = chatTitle.toLowerCase(Locale.ROOT).contains(kw)
                || (customTitle != null && customTitle.toLowerCase(Locale.ROOT).contains(kw));
        if (titleHit && (modelName == null || modelName.isEmpty() || containsModelMessage(msgs, modelName))) {
            String titlePreview = ChatPayloadUtils.buildChatPreview(msgs).replaceAll("\\s+", " ").trim();
            if (titlePreview.length() > 120) titlePreview = titlePreview.substring(0, 120) + "…";
            Map<String, Object> titleItem = new LinkedHashMap<>();
            titleItem.put("resultType", "title");
            titleItem.put("chatId", chatId);
            titleItem.put("chatTitle", customTitle != null ? customTitle : chatTitle);
            titleItem.put("role", "user");
            titleItem.put("snippet", titlePreview);
            matched.add(titleItem);
        }
        for (int messageIndex = 0; messageIndex < msgs.size(); messageIndex++) {
            Map<String, Object> msg = msgs.get(messageIndex);
            if (!(msg.get("content") instanceof String content)) continue;
            // 模型筛选：仅命中指定模型生成的消息（或用户消息始终参与）
            if (modelName != null && !modelName.isEmpty()
                    && "assistant".equals(msg.get("role"))
                    && !modelName.equals(msg.get("modelName"))) {
                continue;
            }
            int pos = content.toLowerCase(Locale.ROOT).indexOf(kw);
            if (pos < 0) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resultType", "message");
            item.put("chatId", chatId);
            item.put("chatTitle", customTitle != null ? customTitle : chatTitle);
            item.put("messageIndex", messageIndex);
            item.put("role", msg.get("role"));
            item.put("time", msg.get("time"));
            item.put("modelName", msg.get("modelName"));
            item.put("snippet", ChatPayloadUtils.buildSnippet(content, pos, kw.length()));
            matched.add(item);
        }
    }

    /** 判断会话中是否存在指定模型生成的消息（标题命中项的模型筛选用） */
    private boolean containsModelMessage(List<Map<String, Object>> msgs, String modelName) {
        for (Map<String, Object> m : msgs) {
            if (modelName.equals(m.get("modelName"))) return true;
        }
        return false;
    }

    // 标题/预览/时间/片段构建统一收敛到 ChatPayloadUtils（与分享等模块共用，消除重复实现）

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

    // ========== 回收站（软删除会话的恢复与彻底删除） ==========

    /**
     * 查询用户回收站会话列表（软删除的会话，按删除时间倒序）
     * @param userId 用户ID
     * @return 回收站会话摘要列表（id/title/preview/lastTime/count/deletedAt）
     */
    public List<Map<String, Object>> listTrash(String userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            ensureSessionMigrated(userId);
            for (Map<String, Object> row : sqliteStorage.listTrashSessions(userId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.get("chat_id"));
                item.put("title", row.get("title") != null ? row.get("title") : "新会话");
                item.put("preview", row.get("preview") != null ? row.get("preview") : "");
                item.put("lastTime", row.get("last_time"));
                item.put("count", row.get("msg_count") instanceof Number n ? n.intValue() : 0);
                item.put("deletedAt", row.get("deleted_at"));
                result.add(item);
            }
        } catch (Exception e) {
            log.error("查询回收站失败: userId={}", userId, e);
        }
        return result;
    }

    /**
     * 恢复回收站中的会话（显式操作）：清除软删除标记并从删除列表移除，
     * 版本号 +1 使其他端刷新可见；原文件夹已删除时归属自动清除（恢复为未分组）。
     * @param userId 用户ID
     * @param chatIds 待恢复的会话ID集合
     * @return 实际恢复的会话ID列表
     */
    @Transactional
    public List<String> restoreFromTrash(String userId, Collection<String> chatIds) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            ensureSessionMigrated(userId);
            List<String> restored = sqliteStorage.restoreChatSessions(userId, chatIds);
            if (restored.isEmpty()) return restored;
            // 从状态表删除列表移除，防止下次同步再次软删除
            Map<String, Object> state = sqliteStorage.loadChatUserState(userId);
            Set<String> deletedIds = new LinkedHashSet<>(parseDeletedIds(state));
            deletedIds.removeAll(restored);
            // 原文件夹已删除的归属清除：恢复为未分组而不是恢复到不存在的文件夹
            Set<String> folderIds = new HashSet<>();
            for (Object f : parseFolders(state)) {
                if (f instanceof Map<?, ?> m && m.get("id") instanceof String fid) folderIds.add(fid);
            }
            for (String chatId : restored) {
                Map<String, Object> row = sqliteStorage.getChatSession(userId, chatId);
                if (row == null || !(row.get("meta") instanceof String s) || s.isEmpty()) continue;
                try {
                    Map<String, Object> meta = objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                    Object fid = meta.get("folderId");
                    if (fid instanceof String folderId && !folderIds.contains(folderId)) {
                        meta.remove("folderId");
                        sqliteStorage.updateChatSessionMetaChecked(userId, chatId,
                                objectMapper.writeValueAsString(meta), null,
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                                System.currentTimeMillis());
                    }
                } catch (Exception ignore) { /* 单条元信息修复失败不阻断恢复 */ }
            }
            String updatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String deletedIdsJson;
            try {
                deletedIdsJson = objectMapper.writeValueAsString(new ArrayList<>(deletedIds));
            } catch (Exception e) {
                deletedIdsJson = "[]";
            }
            sqliteStorage.saveChatUserState(userId,
                    state != null && state.get("last_chat_id") instanceof String s ? s : null,
                    deletedIdsJson,
                    state != null && state.get("folders_json") instanceof String f ? f : null,
                    updatedAt, System.currentTimeMillis());
            sqliteStorage.bumpChatSyncSeq(userId);
            log.info("回收站恢复会话: userId={}, 恢复数={}", userId, restored.size());
            return restored;
        }
    }

    /**
     * 彻底删除回收站中的会话（不可恢复；chatIds 为空表示清空回收站）
     * @param userId 用户ID
     * @param chatIds 待彻底删除的会话ID集合，空集合表示清空全部回收站
     * @return 删除行数
     */
    @Transactional
    public int purgeTrash(String userId, Collection<String> chatIds) {
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            ensureSessionMigrated(userId);
            int purged = sqliteStorage.purgeChatSessions(userId, chatIds);
            if (purged > 0) {
                sqliteStorage.bumpChatSyncSeq(userId);
                log.info("回收站彻底删除会话: userId={}, 删除数={}", userId, purged);
            }
            return purged;
        }
    }
}
