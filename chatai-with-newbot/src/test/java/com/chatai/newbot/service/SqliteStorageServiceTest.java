package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.ProviderModel;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteStorageService 数据层测试（基于真实 SQLite 临时库，非 Spring 上下文）。
 * 覆盖核心路径：admin 初始化与登录认证、模型配置 CRUD 与失效式缓存一致性、
 * 用户缓存隔离性、每日配额统计的日期范围语义、使用记录写入。
 * <p>
 * 注意：ApiKeyCrypto 的密钥文件基于 user.dir，测试前重定向到临时目录，
 * 避免污染模块目录下的 data/apikey.secret（与 ApiKeyCryptoTest 相同策略）。
 */
class SqliteStorageServiceTest {

    @TempDir
    static Path tempDir;

    private static String originalUserDir;
    private static SqliteStorageService service;

    @BeforeAll
    static void setup() {
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test-chatai.db").toAbsolutePath());
        service = new SqliteStorageService(new JdbcTemplate(dataSource));
        service.init();
    }

    @AfterAll
    static void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    /** 每个用例生成唯一用户名，避免用例间相互影响 */
    private String uniqueName(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    @Test
    void init_自动创建内置admin且可登录() {
        User admin = service.authenticate("admin", "admin123");
        assertNotNull(admin, "默认兜底密码应可登录内置 admin");
        assertEquals("admin", admin.getRole());
        assertTrue(admin.isAdmin());
        assertNull(service.authenticate("admin", "wrong-password"), "错误密码应认证失败");
    }

    @Test
    void register_重复用户名与admin名拒绝() {
        String username = uniqueName("dup");
        assertNotNull(service.register(username, "pass1234", "127.0.0.1"));
        assertNull(service.register(username, "pass1234", "127.0.0.1"), "重复用户名应注册失败");
        assertNull(service.register("admin", "pass1234", "127.0.0.1"), "admin 用户名应被拒绝");
    }

    /**
     * 双重验证凭据应可启用、阻止 TOTP 时间步重放、一次性消费恢复码并完整清除。
     */
    @Test
    void twoFactor_凭据生命周期与一次性语义() {
        User user = service.register(uniqueName("two_factor"), "pass1234", "127.0.0.1");
        assertNotNull(user);
        assertTrue(service.enableTwoFactor(user.getId(), "ENC:test-secret", List.of("hash-a", "hash-b")));

        User enabled = service.getUserById(user.getId());
        assertTrue(enabled.isTwoFactorEnabled());
        assertEquals(2, enabled.getRecoveryCodeHashes().size());
        assertTrue(service.claimTwoFactorStep(user.getId(), 100L));
        assertFalse(service.claimTwoFactorStep(user.getId(), 100L), "同一时间步必须拒绝重放");
        assertTrue(service.consumeRecoveryCode(user.getId(), "hash-a"));
        assertFalse(service.consumeRecoveryCode(user.getId(), "hash-a"), "恢复码必须只能使用一次");
        assertTrue(service.replaceRecoveryCodes(user.getId(), List.of("hash-new")));
        assertEquals(List.of("hash-new"), service.getUserById(user.getId()).getRecoveryCodeHashes());

        assertTrue(service.disableTwoFactor(user.getId()));
        User disabled = service.getUserById(user.getId());
        assertFalse(disabled.isTwoFactorEnabled());
        assertTrue(disabled.getRecoveryCodeHashes().isEmpty());
        assertNull(disabled.getTwoFactorSecret());
    }

    @Test
    void modelConfigCrud_缓存一致性() {
        ModelConfig config = new ModelConfig();
        config.setProviderId("custom-test");
        config.setProviderName("测试厂商");
        config.setModelId("test-model-1");
        config.setDisplayName("测试模型");
        config.setApiKey("sk-test");
        config.setApiUrl("http://localhost/v1/");
        config.setEnabled(true);
        config.setInputPriceCny(2.5);
        config.setOutputPriceCny(10);
        config.setCachedPriceCny(0.5);
        config.setReasoningPriceCny(12);
        ModelConfig saved = service.addModelConfig(config);
        assertNotNull(saved.getId());

        // 缓存命中路径：按 ID 取到的应与新增一致
        ModelConfig byId = service.getModelConfigById(saved.getId());
        assertNotNull(byId);
        assertEquals("测试模型", byId.getDisplayName());
        assertEquals(2.5, byId.getInputPriceCny(), 0.000001);

        // 写后缓存失效：更新显示名后应读到新值
        saved.setDisplayName("测试模型-改");
        service.updateModelConfig(saved);
        assertEquals("测试模型-改", service.getModelConfigById(saved.getId()).getDisplayName());

        // 健康检查开关：新增未显式设置时默认参与（NULL -> true），显式关闭后持久化生效
        assertEquals(Boolean.TRUE, service.getModelConfigById(saved.getId()).getHealthCheckEnabled());
        saved.setHealthCheckEnabled(false);
        service.updateModelConfig(saved);
        assertEquals(Boolean.FALSE, service.getModelConfigById(saved.getId()).getHealthCheckEnabled());

        // 删除后缓存同步失效
        assertTrue(service.deleteModelConfig(saved.getId()));
        assertNull(service.getModelConfigById(saved.getId()), "删除后按 ID 应查不到");
        assertNull(service.getModelConfigById("not-exist-id"));
    }

    /** 新自定义厂商必须获得唯一 ID、默认图标，并在后续模型中复用完整厂商配置。 */
    @Test
    void customProvider_唯一标识与配置自动复用() {
        ModelConfig first = new ModelConfig();
        first.setProviderId("custom");
        first.setProviderName("厂商" + uniqueName("alpha"));
        first.setModelId("model-a");
        first.setDisplayName("模型 A");
        first.setApiUrl("https://example.test/v1");
        first.setApiKey("sk-a");
        first.setEnabled(true);
        service.addModelConfig(first);

        ModelConfig otherProvider = new ModelConfig();
        otherProvider.setProviderId("custom");
        otherProvider.setProviderName("厂商" + uniqueName("beta"));
        otherProvider.setModelId("model-b");
        otherProvider.setDisplayName("模型 B");
        otherProvider.setApiUrl("https://other.test/v1");
        otherProvider.setApiKey("sk-b");
        otherProvider.setEnabled(true);
        service.addModelConfig(otherProvider);

        assertNotEquals(first.getProviderId(), otherProvider.getProviderId());
        assertTrue(first.getProviderId().startsWith("custom-"));
        assertFalse(first.getProviderIcon().isBlank());

        ModelConfig reused = new ModelConfig();
        reused.setProviderId(first.getProviderId());
        reused.setModelId("model-a-2");
        reused.setDisplayName("模型 A2");
        reused.setApiKey("sk-new");
        reused.setEnabled(true);
        service.addModelConfig(reused);
        assertEquals(first.getProviderName(), reused.getProviderName());
        assertEquals(first.getProviderIcon(), reused.getProviderIcon());
        assertEquals(first.getApiUrl(), reused.getApiUrl());

        ProviderModel catalogModel = new ProviderModel();
        catalogModel.setId("catalog-only-model");
        catalogModel.setName("目录模型");
        service.saveProviderModels(first.getProviderId(), List.of(catalogModel));
        Map<String, Object> providerRow = service.listCustomProviders().stream()
                .filter(row -> first.getProviderId().equals(row.get("id")))
                .findFirst().orElseThrow();
        assertEquals(1, ((Number) providerRow.get("modelCount")).intValue(),
                "厂商模型数必须按支持目录统计，不能按已接入配置数统计");
        assertEquals(1, ((List<?>) providerRow.get("models")).size());
    }

    /** 分享复制授权应允许被授权用户读取，同时查询参数不能绕过所有权校验。 */
    @Test
    void fileAsset_所有权与复制授权边界() {
        String url = "/api/files/img/asset-test.png";
        service.registerFileAsset(url, "owner-user", "image");
        assertTrue(service.canAccessFileAsset(url, "owner-user", false));
        assertFalse(service.canAccessFileAsset(url + "?shareId=guess", "other-user", false));

        service.grantFileAssetAccess(url, "other-user");
        assertTrue(service.canAccessFileAsset(url + "?shareId=valid", "other-user", false));
    }

    @Test
    void calculateCostCny_按分类Token计费且不重复计算() {
        ModelConfig model = new ModelConfig();
        model.setProviderId("custom-billing");
        model.setProviderName("计费测试");
        model.setModelId("billing-model");
        model.setDisplayName("计费模型");
        model.setApiKey("sk-test");
        model.setEnabled(true);
        model.setInputPriceCny(2);
        model.setOutputPriceCny(8);
        model.setCachedPriceCny(0.5);
        model.setReasoningPriceCny(10);
        service.addModelConfig(model);

        UsageLog usage = new UsageLog();
        usage.setModelId(model.getId());
        usage.setPromptTokens(1_000_000);
        usage.setCachedTokens(200_000);
        usage.setCompletionTokens(500_000);
        usage.setReasoningTokens(100_000);
        // 普通输入 80万*2 + 缓存20万*0.5 + 普通输出40万*8 + 推理10万*10 = 5.9 元
        assertEquals(5.9, service.calculateCostCny(usage), 0.000001);
    }

    @Test
    void getUserById_返回副本不污染缓存() {
        String username = uniqueName("cache");
        User created = service.register(username, "pass1234", "127.0.0.1");
        User first = service.getUserById(created.getId());
        User second = service.getUserById(created.getId());
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "两次获取应返回不同对象（防御性副本）");
        // 修改副本不应影响下一次读取
        first.setUsername("被篡改");
        assertEquals(username, service.getUserById(created.getId()).getUsername());
    }

    @Test
    void countUsageByUserAndDay_日期范围语义() {
        String username = uniqueName("usage");
        User user = service.register(username, "pass1234", "127.0.0.1");
        String today = LocalDate.now().toString();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        UsageLog logToday = new UsageLog();
        logToday.setUserId(user.getId());
        logToday.setUsername(username);
        logToday.setModelId("m1");
        logToday.setModelName("模型1");
        logToday.setTimestamp(LocalDateTime.now().format(fmt));
        logToday.setPromptTokens(10);
        logToday.setCompletionTokens(5);
        service.addUsageLog(logToday);

        // 昨天边界外的记录不应计入今天
        UsageLog logYesterday = new UsageLog();
        logYesterday.setUserId(user.getId());
        logYesterday.setUsername(username);
        logYesterday.setModelId("m1");
        logYesterday.setModelName("模型1");
        logYesterday.setTimestamp(LocalDate.now().minusDays(1).atTime(23, 59, 59).format(fmt));
        logYesterday.setPromptTokens(100);
        logYesterday.setCompletionTokens(100);
        service.addUsageLog(logYesterday);

        assertEquals(1, service.countUsageByUserAndDay(user.getId(), today), "今日应仅计 1 条");
        assertEquals(15L, service.sumTokensByUserAndDay(user.getId(), today), "今日 Token 应为 10+5");
    }

    @Test
    void changePassword_旧密码校验与新密码生效() {
        String username = uniqueName("pwd");
        User user = service.register(username, "oldpass123", "127.0.0.1");
        assertEquals(2, service.changePassword(user.getId(), "wrong", "newpass123"), "旧密码错误应返回 2");
        assertEquals(0, service.changePassword(user.getId(), "oldpass123", "newpass123"));
        assertNotNull(service.authenticate(username, "newpass123"), "新密码应可登录");
        assertNull(service.authenticate(username, "oldpass123"), "旧密码应不可再登录");
    }

    @Test
    void deleteUser_admin不可删除() {
        User admin = service.authenticate("admin", "admin123");
        assertNotNull(admin);
        assertFalse(service.deleteUser(admin.getId()), "内置 admin 应受保护不可删除");
        assertFalse(service.deleteUser("not-exist-user"), "不存在用户删除应返回 false");
    }

    /** 跨会话搜索应同时命中标题和消息正文，并返回可直接跳转的消息绝对下标。 */
    @Test
    void chatSearch_标题与消息内容均可定位() {
        String userId = uniqueName("chat_search");
        String messages = "[{\"role\":\"user\",\"content\":\"项目背景\"},"
                + "{\"role\":\"assistant\",\"content\":\"Needle response\",\"time\":\"08:00\"}]";
        service.upsertChatSession(userId, "chat-search", messages, "{}",
                "Release Planning", "项目背景", "08:00", 2,
                "2026-08-25 08:00:00", System.currentTimeMillis());
        ChatHistoryService historyService = new ChatHistoryService(service);

        List<Map<String, Object>> titleResults = historyService.searchChatHistory(userId, "release", 50);
        assertEquals(1, titleResults.size());
        assertEquals("title", titleResults.get(0).get("resultType"));
        assertEquals("chat-search", titleResults.get(0).get("chatId"));
        assertEquals("项目背景", titleResults.get(0).get("snippet"));

        List<Map<String, Object>> messageResults = historyService.searchChatHistory(userId, "NEEDLE", 50);
        assertEquals(1, messageResults.size());
        assertEquals("message", messageResults.get(0).get("resultType"));
        assertEquals(1, ((Number) messageResults.get(0).get("messageIndex")).intValue());
    }
}
