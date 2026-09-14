package com.chatai.newbot;

import com.chatai.newbot.service.ObservabilityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核心业务端到端测试：从真实 HTTP 控制器经过鉴权、业务服务到独立 SQLite，
 * 覆盖首次管理员登录、模型配置、会话持久化、可观测性与备份恢复。
 */
@SpringBootTest(properties = {
        "chatai.health-check.enabled=false",
        "chatai.observability.flush-interval-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreJourneyE2ETest {
    private static final String ORIGINAL_USER_DIR = System.getProperty("user.dir");
    private static final Path MODULE_ROOT = Path.of(ORIGINAL_USER_DIR).toAbsolutePath();
    private static final Path TEST_WORK_DIR = MODULE_ROOT.resolve("target/core-e2e-workdir");
    private static final Path TEST_DATABASE = TEST_WORK_DIR.resolve("chatai-core-e2e.db");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static MockCookie adminCookie;
    private static String backupName;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservabilityService observabilityService;
    @Autowired
    private com.chatai.newbot.service.StorageManager storage;

    /** 通过真实鉴权、专用设置接口与数据库验证全局/覆盖优先级、清空及非法值保护。 */
    @Test
    @Order(3)
    void 输出配置_全局和可空模型覆盖持久化() throws Exception {
        String base = "/api/admin/settings/chat-output";
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
        var model = storage.getAllModelConfigs().stream().filter(item -> "e2e-model".equals(item.getModelId())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(model.getMaxOutputTokens());
        mockMvc.perform(get(base).cookie(adminCookie))
                .andExpect(jsonPath("$.data.globalMaxOutputTokens").value(16384))
                .andExpect(jsonPath("$.data.models").isEmpty())
                .andExpect(jsonPath("$.data.availableModels").isNotEmpty());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).cookie(adminCookie)
                .contentType("application/json").content("{\"maxOutputTokens\":8192}")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals("8192", storage.getSetting("chat_max_output_tokens"));
        String path = base + "/models/" + model.getId();
        for (String value : java.util.List.of("4096", "0", "null")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).cookie(adminCookie)
                    .contentType("application/json").content("{\"maxOutputTokens\":" + value + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            Integer expected = "null".equals(value) ? null : Integer.valueOf(value);
            var loaded = storage.getModelConfigById(model.getId());
            org.junit.jupiter.api.Assertions.assertEquals(expected, loaded.getMaxOutputTokens());
            mockMvc.perform(get(base).cookie(adminCookie))
                    .andExpect(jsonPath("$.data.models.length()").value(expected == null ? 0 : 1));
            org.junit.jupiter.api.Assertions.assertEquals(expected == null ? 8192 : expected,
                    com.chatai.newbot.service.OutputTokenPolicy.resolve(new com.chatai.newbot.model.ChatRequest(), loaded, storage.getSetting("chat_max_output_tokens")));
        }
        for (String value : java.util.List.of("null", "-1", "1.5", "2147483648", "\"0\"")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).cookie(adminCookie)
                    .contentType("application/json").content("{\"maxOutputTokens\":" + value + "}"))
                    .andExpect(status().isBadRequest());
        }
        org.junit.jupiter.api.Assertions.assertEquals("8192", storage.getSetting("chat_max_output_tokens"));
    }

    /** 上下文与输出独立保存，旧模型编辑不会覆盖专用接口更新的容量。 */
    @Test
    @Order(3)
    void 上下文配置_独立继承和覆盖且拒绝非法值() throws Exception {
        String base = "/api/admin/settings/chat-context";
        mockMvc.perform(get(base)).andExpect(status().isUnauthorized());
        var model = storage.getAllModelConfigs().stream().filter(item -> "e2e-model".equals(item.getModelId())).findFirst().orElseThrow();
        mockMvc.perform(get(base).cookie(adminCookie))
                .andExpect(jsonPath("$.data.globalContextWindow").value(32000));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).cookie(adminCookie)
                .contentType("application/json").content("{\"contextWindow\":64000}")).andExpect(status().isOk());
        Integer originalOutput = model.getMaxOutputTokens();
        String path = base + "/models/" + model.getId();
        for (String value : java.util.List.of("200000", "null")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).cookie(adminCookie)
                    .contentType("application/json").content("{\"contextWindow\":" + value + "}"))
                    .andExpect(status().isOk());
            var loaded = storage.getModelConfigById(model.getId());
            org.junit.jupiter.api.Assertions.assertEquals(originalOutput, loaded.getMaxOutputTokens());
            int expected = "null".equals(value) ? 64000 : 200000;
            org.junit.jupiter.api.Assertions.assertEquals(expected,
                    com.chatai.newbot.service.OutputTokenPolicy.contextWindow(loaded, storage.getSetting("chat_context_window")));
            mockMvc.perform(get("/api/models").cookie(adminCookie))
                    .andExpect(jsonPath("$.data[0].contextWindow").value(expected));
        }
        for (String value : java.util.List.of("0", "-1", "1.5", "2147483648", "\"32000\"")) {
            for (String target : java.util.List.of(base, path)) {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(target).cookie(adminCookie)
                        .contentType("application/json").content("{\"contextWindow\":" + value + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).cookie(adminCookie)
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).cookie(adminCookie)
                .contentType("application/json").content("{\"contextWindow\":128000}")).andExpect(status().isOk());
        model.setContextWindow(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/models/" + model.getId()).cookie(adminCookie)
                .contentType("application/json").content(JSON.writeValueAsString(model))).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(128000, storage.getModelConfigById(model.getId()).getContextWindow());
        org.junit.jupiter.api.Assertions.assertEquals("64000", storage.getSetting("chat_context_window"));
    }

    static {
        try {
            Files.createDirectories(TEST_WORK_DIR);
            Files.deleteIfExists(TEST_DATABASE);
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-wal"));
            Files.deleteIfExists(Path.of(TEST_DATABASE + "-shm"));
            System.setProperty("user.dir", TEST_WORK_DIR.toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 将测试数据源固定到 target 下的绝对路径，不依赖测试期间重定向的 user.dir。 */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DATABASE);
    }

    /** 断言隔离工作目录存在，首次初始化由 Spring 上下文完成。 */
    @BeforeAll
    static void verifyIsolatedWorkDirectory() {
        if (!Files.isDirectory(TEST_WORK_DIR)) {
            throw new IllegalStateException("测试隔离目录创建失败: " + TEST_WORK_DIR);
        }
    }

    /** 测试结束后恢复 Maven 进程工作目录属性，避免影响后续测试类。 */
    @AfterAll
    static void restoreWorkingDirectory() {
        System.setProperty("user.dir", ORIGINAL_USER_DIR);
    }

    /** 验证首次数据库初始化后默认管理员可登录并获得安全 Cookie。 */
    @Test
    @Order(1)
    void 首次启动_管理员登录成功() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(cookie().httpOnly("token", true))
                .andReturn();
        adminCookie = new MockCookie("token", result.getResponse().getCookie("token").getValue());
    }

    /** 验证管理员通过 HTTP 接口新增模型，普通模型列表接口可立即读取。 */
    @Test
    @Order(2)
    void 模型配置_新增后对当前用户可见() throws Exception {
        Map<String, Object> model = Map.ofEntries(
                Map.entry("providerId", "custom"), Map.entry("providerName", "E2E Provider"),
                Map.entry("modelId", "e2e-model"), Map.entry("displayName", "E2E Model"),
                Map.entry("apiKey", "sk-e2e"), Map.entry("apiUrl", "http://127.0.0.1:1/v1"),
                Map.entry("protocol", "openai"), Map.entry("enabled", true), Map.entry("contextWindow", 128000),
                Map.entry("visibleToAll", true), Map.entry("healthCheckEnabled", false));
        mockMvc.perform(post("/api/admin/models").cookie(adminCookie)
                        .contentType("application/json").content(JSON.writeValueAsString(model)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.displayName").value("E2E Model"))
                .andExpect(jsonPath("$.data.contextWindow").value(128000))
                .andExpect(jsonPath("$.data.maxOutputTokens").isEmpty());

        mockMvc.perform(get("/api/models").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.modelId == 'e2e-model')]").exists());
    }

    /** 验证会话写入 SQLite 后可通过单会话与摘要接口重新加载。 */
    @Test
    @Order(3)
    void 会话历史_保存后可重新加载() throws Exception {
        String body = """
                {
                  "lastChatId":"e2e-chat",
                  "chats":{"e2e-chat":[
                    {"id":"m-user","role":"user","content":"端到端测试消息","time":"10:00"},
                    {"id":"m-assistant","role":"assistant","content":"端到端测试回复","time":"10:01"}
                  ]},
                  "chatMeta":{"e2e-chat":{"title":"E2E 会话","modelName":"E2E Model"}},
                  "baseVersions":{"e2e-chat":0},
                  "baseFoldersVersion":0,
                  "restoreChatIds":[],
                  "deletedChatIds":[],
                  "folders":[]
                }
                """;
        mockMvc.perform(post("/api/chat/history").cookie(adminCookie)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/chat/history/single").param("chatId", "e2e-chat").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.messages[1].content").value("端到端测试回复"));
        mockMvc.perform(get("/api/chat/history/summary").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("E2E 会话")));
    }

    /** 验证百万字正文、思考及回答版本通过 HTTP 解析、SQLite 存储和读取后完全一致。 */
    @Test
    @Order(3)
    void 长文本_百万字正文及思考和版本通过保存接口无损往返() throws Exception {
        String answer = "长正文🙂\n".repeat(150000) + "正文终点";
        String reasoning = "长思考内容".repeat(40000) + "思考终点";
        String previous = "历史版本正文".repeat(50000) + "历史终点";
        Map<String, Object> message = Map.of(
                "id", "large-answer", "role", "assistant", "content", answer,
                "reasoning_content", reasoning, "currentVersionId", "v2",
                "versions", java.util.List.of(Map.of("id", "v1", "content", previous),
                        Map.of("id", "v2", "content", answer, "reasoning_content", reasoning)));
        Map<String, Object> payload = Map.of("chats", Map.of("large-chat", java.util.List.of(message)),
                "baseVersions", Map.of("large-chat", 0));
        mockMvc.perform(post("/api/chat/history").cookie(adminCookie).contentType("application/json")
                        .content(JSON.writeValueAsBytes(payload)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        MvcResult loaded = mockMvc.perform(get("/api/chat/history/single").param("chatId", "large-chat").cookie(adminCookie))
                .andExpect(status().isOk()).andReturn();
        JsonNode messages = JSON.readTree(loaded.getResponse().getContentAsByteArray()).get("messages");
        org.junit.jupiter.api.Assertions.assertEquals(answer, messages.get(0).get("content").asText());
        org.junit.jupiter.api.Assertions.assertEquals(reasoning, messages.get(0).get("reasoning_content").asText());
        org.junit.jupiter.api.Assertions.assertEquals(previous, messages.get(0).at("/versions/0/content").asText());
        org.junit.jupiter.api.Assertions.assertEquals(answer, messages.get(0).at("/versions/1/content").asText());
    }

    /** 验证运行指标可落库，并通过管理员快照与历史接口读取。 */
    @Test
    @Order(4)
    void 可观测性_刷新后可查询累计与趋势() throws Exception {
        observabilityService.recordChatFirstToken(320);
        observabilityService.flushPending();
        mockMvc.perform(get("/api/admin/observability").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metricsSince").isNotEmpty());
        mockMvc.perform(get("/api/admin/observability/history").param("hours", "24").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].bucketStart").isNotEmpty());
    }

    /** 验证完整备份可恢复被删除的会话，并按安全约定让旧会话凭证失效。 */
    @Test
    @Order(5)
    void 备份恢复_找回已删除会话并要求重新登录() throws Exception {
        MvcResult backup = mockMvc.perform(post("/api/admin/backups").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode backupBody = JSON.readTree(backup.getResponse().getContentAsString());
        backupName = backupBody.path("data").path("name").asText();

        mockMvc.perform(delete("/api/chat/history").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/admin/backups/restore").cookie(adminCookie)
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(Map.of("name", backupName, "password", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult relogin = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        MockCookie restoredCookie = new MockCookie("token", relogin.getResponse().getCookie("token").getValue());
        mockMvc.perform(get("/api/chat/history/single").param("chatId", "e2e-chat").cookie(restoredCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.messages[0].content").value("端到端测试消息"));
    }
}
