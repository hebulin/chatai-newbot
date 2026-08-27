package com.chatai.newbot.service;

import com.chatai.newbot.exception.ChatSyncConflictException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatHistoryService 多端同步测试（基于真实 SQLite 临时库，非 Spring 上下文）。
 * 覆盖：双端同时追加的版本冲突、相同消息数量但内容变更的识别、
 * 删除与编辑冲突（已删会话不被静默复活）、显式恢复、文件夹并发修改、
 * 元信息独立更新与全局同步序列号的单调递增。
 */
class ChatHistoryServiceTest {

    @TempDir
    static Path tempDir;

    private static String originalUserDir;
    private static SqliteStorageService storage;
    private static ChatHistoryService service;

    @BeforeAll
    static void setup() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test-chat-sync.db").toAbsolutePath());
        storage = new SqliteStorageService(new JdbcTemplate(dataSource));
        storage.init();
        service = new ChatHistoryService(storage);
    }

    @AfterAll
    static void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** 构造一条用户消息 Map */
    private Map<String, Object> userMsg(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", content);
        m.put("time", "2026-08-26 10:00:00");
        return m;
    }

    /** 构造一条助手消息 Map */
    private Map<String, Object> assistantMsg(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", content);
        m.put("time", "2026-08-26 10:00:01");
        return m;
    }

    /** 组装一次保存请求体（含逐会话基准版本） */
    private Map<String, Object> payload(Map<String, List<Map<String, Object>>> chats,
                                         Map<String, Long> baseVersions) {
        Map<String, Object> body = new HashMap<>();
        body.put("chats", new LinkedHashMap<>(chats));
        body.put("chatMeta", new HashMap<>());
        body.put("deletedChatIds", new ArrayList<String>());
        if (baseVersions != null) {
            body.put("baseVersions", new HashMap<>(baseVersions));
        }
        return body;
    }

    /** 从保存结果中提取冲突列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conflictsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("conflicts");
    }

    /** 从保存结果中提取每会话新版本映射 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> versionsOf(Map<String, Object> result) {
        return (Map<String, Object>) result.get("versions");
    }

    /** 双端基于同一版本同时追加：后到者必须收到版本冲突而非静默覆盖 */
    @Test
    void save_双端基于同一版本并发修改_后到者收到冲突() {
        String userId = "sync_user_" + System.nanoTime();
        // 端 A 创建会话（新会话基准版本 0）
        Map<String, Object> first = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("你好"))), Map.of("c1", 0L)));
        assertTrue(conflictsOf(first).isEmpty());
        assertEquals(1L, ((Number) versionsOf(first).get("c1")).longValue());
        long seqAfterFirst = ((Number) first.get("version")).longValue();
        assertTrue(seqAfterFirst > 0, "保存后全局同步序列号应大于 0");

        // 端 A 基于 v1 追加消息 -> v2
        Map<String, Object> aSecond = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("你好"), assistantMsg("A端回答"))), Map.of("c1", 1L)));
        assertTrue(conflictsOf(aSecond).isEmpty());
        assertEquals(2L, ((Number) versionsOf(aSecond).get("c1")).longValue());
        long seqAfterA2 = ((Number) aSecond.get("version")).longValue();
        assertTrue(seqAfterA2 > seqAfterFirst, "同步序列号必须单调递增");

        // 端 B 仍基于 v1 追加（未感知 A 的写入）-> 必须冲突，且服务端内容保持 A 的版本
        Map<String, Object> bStale = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("你好"), assistantMsg("B端回答"))), Map.of("c1", 1L)));
        List<Map<String, Object>> conflicts = conflictsOf(bStale);
        assertEquals(1, conflicts.size(), "基于过期版本的写入必须返回冲突");
        assertEquals("c1", conflicts.get(0).get("chatId"));
        assertEquals("version", conflicts.get(0).get("reason"));
        assertEquals(2L, ((Number) conflicts.get(0).get("serverVersion")).longValue());
        // 服务端未被覆盖
        Map<String, Object> row = service.loadSingleChat(userId, "c1");
        assertEquals(2L, ((Number) row.get("version")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) row.get("messages");
        assertEquals("A端回答", msgs.get(1).get("content"), "冲突时服务端内容不得被覆盖");

        // 端 B 拉取后以 v2 为基准重传 -> 成功
        Map<String, Object> bRetry = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("你好"), assistantMsg("A端回答"), userMsg("B端追问"))),
                Map.of("c1", 2L)));
        assertTrue(conflictsOf(bRetry).isEmpty());
        assertEquals(3L, ((Number) versionsOf(bRetry).get("c1")).longValue());
    }

    /** 相同消息数量但内容变化（重新生成/编辑）：版本仍递增，冲突检测不依赖条数 */
    @Test
    void save_相同条数内容变更_版本照常递增() {
        String userId = "sync_user_" + System.nanoTime();
        Map<String, Object> created = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("问题"), assistantMsg("旧回答"))), Map.of("c1", 0L)));
        assertEquals(1L, ((Number) versionsOf(created).get("c1")).longValue());

        // 重新生成：条数不变（仍是 2 条），内容变化 -> 版本应 +1
        Map<String, Object> regenerated = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("问题"), assistantMsg("新回答"))), Map.of("c1", 1L)));
        assertEquals(2L, ((Number) versionsOf(regenerated).get("c1")).longValue());
        Map<String, Object> row = service.loadSingleChat(userId, "c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) row.get("messages");
        assertEquals("新回答", msgs.get(1).get("content"));
    }

    /** 删除与编辑冲突：已删会话的迟到上传被拒绝且不复活；显式恢复才可写回 */
    @Test
    void save_已删会话不自动复活_显式恢复可写回() {
        String userId = "sync_user_" + System.nanoTime();
        service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("会话内容"))), Map.of("c1", 0L)));

        // 端 A 删除该会话
        Map<String, Object> deleteReq = payload(Map.of(), Map.of());
        deleteReq.put("deletedChatIds", List.of("c1"));
        Map<String, Object> deleted = service.saveChatHistory(userId, deleteReq);
        assertTrue(conflictsOf(deleted).isEmpty());
        assertFalse((Boolean) service.loadSingleChat(userId, "c1").get("exists"), "删除后会话行应不存在");

        // 端 B（陈旧客户端）上传该会话 -> 返回 deleted 冲突，不复活
        Map<String, Object> staleUpload = service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("会话内容"), assistantMsg("迟到回答"))), Map.of("c1", 1L)));
        List<Map<String, Object>> conflicts = conflictsOf(staleUpload);
        assertEquals(1, conflicts.size());
        assertEquals("deleted", conflicts.get(0).get("reason"));
        assertFalse((Boolean) service.loadSingleChat(userId, "c1").get("exists"), "迟到上传不得复活已删会话");

        // 用户显式恢复 -> 写入成功
        Map<String, Object> restoreReq = payload(
                Map.of("c1", List.of(userMsg("会话内容"), assistantMsg("迟到回答"))), Map.of("c1", 1L));
        restoreReq.put("restoreChatIds", List.of("c1"));
        Map<String, Object> restored = service.saveChatHistory(userId, restoreReq);
        assertTrue(conflictsOf(restored).isEmpty());
        assertTrue((Boolean) service.loadSingleChat(userId, "c1").get("exists"), "显式恢复应写回会话");
    }

    /** 文件夹并发修改：基准版本不一致时返回 folders 冲突且不覆盖服务端定义 */
    @Test
    void save_文件夹并发修改_返回冲突不覆盖() {
        String userId = "sync_user_" + System.nanoTime();
        // 初始保存，建立文件夹版本（基准 0）
        Map<String, Object> init = payload(Map.of(), Map.of());
        init.put("folders", List.of(Map.of("id", "f1", "name", "工作", "collapsed", false)));
        init.put("baseFoldersVersion", 0L);
        Map<String, Object> initResult = service.saveChatHistory(userId, init);
        assertEquals(1L, ((Number) initResult.get("foldersVersion")).longValue());

        // 端 A 修改文件夹（基准 1 -> 2）
        Map<String, Object> aChange = payload(Map.of(), Map.of());
        aChange.put("folders", List.of(Map.of("id", "f1", "name", "工作A", "collapsed", false)));
        aChange.put("baseFoldersVersion", 1L);
        Map<String, Object> aResult = service.saveChatHistory(userId, aChange);
        assertEquals(2L, ((Number) aResult.get("foldersVersion")).longValue());

        // 端 B 基于过期基准 1 修改 -> 冲突且不覆盖
        Map<String, Object> bChange = payload(Map.of(), Map.of());
        bChange.put("folders", List.of(Map.of("id", "f1", "name", "工作B", "collapsed", false)));
        bChange.put("baseFoldersVersion", 1L);
        Map<String, Object> bResult = service.saveChatHistory(userId, bChange);
        List<Map<String, Object>> conflicts = conflictsOf(bResult);
        assertEquals(1, conflicts.size());
        assertEquals("__folders__", conflicts.get(0).get("chatId"));
        Map<String, Object> summaries = service.loadChatSummaries(userId);
        @SuppressWarnings("unchecked")
        List<Object> folders = (List<Object>) summaries.get("folders");
        assertEquals("工作A", ((Map<?, ?>) folders.get(0)).get("name"), "文件夹冲突时服务端定义不得被覆盖");
    }

    /** 元信息独立更新（置顶/重命名不带消息正文）：版本递增且消息正文不受影响 */
    @Test
    void save_元信息独立更新_版本递增且正文不变() {
        String userId = "sync_user_" + System.nanoTime();
        service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("正文保持"))), Map.of("c1", 0L)));

        Map<String, Object> metaReq = payload(Map.of(), Map.of("c1", 1L));
        Map<String, Object> meta = new HashMap<>();
        meta.put("pinned", true);
        metaReq.put("chatMeta", Map.of("c1", meta));
        Map<String, Object> result = service.saveChatHistory(userId, metaReq);
        assertTrue(conflictsOf(result).isEmpty());
        assertEquals(2L, ((Number) versionsOf(result).get("c1")).longValue());

        Map<String, Object> row = service.loadSingleChat(userId, "c1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) row.get("messages");
        assertEquals("正文保持", msgs.get(0).get("content"), "元信息更新不得触碰消息正文");
        @SuppressWarnings("unchecked")
        Map<String, Object> savedMeta = (Map<String, Object>) row.get("meta");
        assertEquals(Boolean.TRUE, savedMeta.get("pinned"));

        // 摘要应携带每会话版本号与文件夹版本号
        Map<String, Object> summaries = service.loadChatSummaries(userId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) summaries.get("summaries");
        assertEquals(2L, ((Number) list.get(0).get("version")).longValue());
        assertNotNull(summaries.get("foldersVersion"));
    }

    /** 存储层乐观锁：并发更新同一行时后到者抛冲突异常 */
    @Test
    void storage_版本条件更新_并发覆盖被拒绝() {
        String userId = "sync_user_" + System.nanoTime();
        String ts = "2026-08-26 10:00:00";
        long v1 = storage.upsertChatSessionChecked(userId, "c1", "[]", null,
                "t", "p", null, 0, ts, 1L, null);
        assertEquals(1L, v1);
        long v2 = storage.upsertChatSessionChecked(userId, "c1", "[]", null,
                "t", "p", null, 0, ts, 2L, 1L);
        assertEquals(2L, v2);
        // 基于过期版本 1 的写入必须抛冲突
        ChatSyncConflictException ex = assertThrows(ChatSyncConflictException.class, () ->
                storage.upsertChatSessionChecked(userId, "c1", "[]", null,
                        "t", "p", null, 0, ts, 3L, 1L));
        assertEquals(2L, ex.getServerVersion());
        // 客户端以为行存在但服务端已删除：基准版本 >0 必须抛冲突
        storage.deleteChatSessions(userId, List.of("c1"));
        assertThrows(ChatSyncConflictException.class, () ->
                storage.upsertChatSessionChecked(userId, "c1", "[]", null,
                        "t", "p", null, 0, ts, 4L, 2L));
        // 基准 0（新会话）可正常插入
        assertEquals(1L, storage.upsertChatSessionChecked(userId, "c1", "[]", null,
                "t", "p", null, 0, ts, 5L, 0L));
    }

    /** 回收站：软删除可查询、显式恢复后回到正常列表、彻底删除后不可恢复 */
    @Test
    void trash_软删除_恢复_彻底删除() {
        String userId = "trash_user_" + System.nanoTime();
        service.saveChatHistory(userId, payload(
                Map.of("c1", List.of(userMsg("待删除会话"))), Map.of("c1", 0L)));

        // 软删除：正常列表不可见，回收站可见
        Map<String, Object> deleteReq = payload(Map.of(), Map.of());
        deleteReq.put("deletedChatIds", List.of("c1"));
        service.saveChatHistory(userId, deleteReq);
        assertFalse((Boolean) service.loadSingleChat(userId, "c1").get("exists"), "软删除后正常查询不可见");
        List<Map<String, Object>> trash = service.listTrash(userId);
        assertEquals(1, trash.size(), "回收站应列出软删除会话");
        assertEquals("c1", trash.get(0).get("id"));
        assertNotNull(trash.get(0).get("deletedAt"));

        // 显式恢复：回到正常列表，版本号递增触发多端刷新
        List<String> restored = service.restoreFromTrash(userId, List.of("c1"));
        assertEquals(List.of("c1"), restored);
        Map<String, Object> row = service.loadSingleChat(userId, "c1");
        assertTrue((Boolean) row.get("exists"), "恢复后会话应可见");
        assertEquals(2L, ((Number) row.get("version")).longValue(), "恢复后版本号应递增");
        assertTrue(service.listTrash(userId).isEmpty(), "恢复后回收站应为空");

        // 再次删除并彻底清除：物理删除不可恢复
        Map<String, Object> deleteReq2 = payload(Map.of(), Map.of());
        deleteReq2.put("deletedChatIds", List.of("c1"));
        service.saveChatHistory(userId, deleteReq2);
        assertEquals(1, service.purgeTrash(userId, List.of("c1")));
        assertTrue(service.listTrash(userId).isEmpty());
        assertFalse((Boolean) service.loadSingleChat(userId, "c1").get("exists"));
        // 彻底删除后恢复操作无效
        assertTrue(service.restoreFromTrash(userId, List.of("c1")).isEmpty());
    }

    /** 回收站恢复时原文件夹已删除：归属自动清除，不恢复到不存在的文件夹 */
    @Test
    void trash_恢复时清理失效文件夹归属() throws Exception {
        String userId = "trash_user_" + System.nanoTime();
        // 创建会话并归入文件夹 f1
        Map<String, Object> create = payload(Map.of("c1", List.of(userMsg("内容"))), Map.of("c1", 0L));
        Map<String, Object> meta = new HashMap<>();
        meta.put("folderId", "f1");
        create.put("chatMeta", Map.of("c1", meta));
        create.put("folders", List.of(Map.of("id", "f1", "name", "工作", "collapsed", false)));
        create.put("baseFoldersVersion", 0L);
        service.saveChatHistory(userId, create);

        // 删除文件夹（folders 置空）+ 删除会话
        Map<String, Object> delFolder = payload(Map.of(), Map.of());
        delFolder.put("folders", List.of());
        delFolder.put("baseFoldersVersion", 1L);
        service.saveChatHistory(userId, delFolder);
        Map<String, Object> delChat = payload(Map.of(), Map.of());
        delChat.put("deletedChatIds", List.of("c1"));
        service.saveChatHistory(userId, delChat);

        // 恢复：folderId 应被清除
        service.restoreFromTrash(userId, List.of("c1"));
        Map<String, Object> row = service.loadSingleChat(userId, "c1");
        @SuppressWarnings("unchecked")
        Map<String, Object> restoredMeta = (Map<String, Object>) row.get("meta");
        assertFalse(restoredMeta.containsKey("folderId"), "原文件夹已删除时不应恢复到不存在的文件夹");
    }
}
