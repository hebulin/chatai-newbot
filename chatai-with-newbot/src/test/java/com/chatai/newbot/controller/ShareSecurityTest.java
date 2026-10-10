package com.chatai.newbot.controller;

import com.chatai.newbot.model.ChatShare;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 分享创建、读取、图片、复制与脱敏的跨入口权限回归。 */
class ShareSecurityTest {
    private final ObjectMapper json = new ObjectMapper();
    private StorageManager storage;
    private ChatHistoryService history;
    private FileAssetRepository assets;
    private ChatShareRepository shares;
    private ShareAccessService access;
    private ShareController controller;
    private MockHttpServletRequest request;
    private ChatShare share;
    private static final String IMAGE = "/api/files/img/202610/owned.png";

    /** 构建共享的权限服务与可控分享记录，不读写实际运行数据库。 */
    @BeforeEach
    void setup() throws Exception {
        storage = mock(StorageManager.class);
        history = mock(ChatHistoryService.class);
        assets = mock(FileAssetRepository.class);
        shares = mock(ChatShareRepository.class);
        access = new ShareAccessService(assets, shares);
        controller = new ShareController(storage, history, access);
        User user = new User();
        user.setId("visitor"); user.setUsername("visitor");
        request = new MockHttpServletRequest(); request.setAttribute("currentUser", user);
        share = new ChatShare(); share.setId("share"); share.setUserId("owner");
        share.setTitle("公开标题"); share.setMaxViews(1);
        share.setSnapshotJson(json.writeValueAsString(List.of(Map.of("role", "user", "content", "内容", "images", List.of(IMAGE)))));
        when(storage.getChatShareById("share")).thenReturn(share);
        when(assets.canShare(IMAGE, "owner")).thenReturn(true);
        when(assets.grantFrom(IMAGE, "owner", "visitor")).thenReturn(true);
        when(shares.claimAccess(any(ChatShare.class))).thenReturn(true);
        when(history.saveChatHistory(eq("visitor"), anyMap())).thenReturn(Map.of("version", 1L));
    }

    /** 攻击者把别人资源地址写入自己的会话，创建与旧快照复制均不能转授权限。 */
    @Test
    void forgedResourceCannotBePublishedOrCloned() throws Exception {
        when(history.loadSingleChat("visitor", "chat")).thenReturn(Map.of("messages", json.readValue(share.getSnapshotJson(), List.class)));
        assertThrows(IllegalArgumentException.class, () -> controller.create(Map.of("chatId", "chat"), request));
        verify(storage, never()).addChatShare(any());
        share.setUserId("visitor");
        assertThrows(IllegalArgumentException.class, () -> controller.cloneShare("share", Map.of(), request));
        verify(assets, never()).grantFrom(anyString(), anyString(), anyString());
        verify(history, never()).saveChatHistory(anyString(), anyMap());
        assertFalse(access.canAccessAsset("/api/files/img/202610/unregistered.png", "visitor"));
    }

    /** 最后一次合法阅读签发图片凭证；无凭证、改密、撤销及其它图片全部拒绝。 */
    @Test
    void imageRequiresAuthorizedViewAndTokenTracksPolicy() {
        FileStorageService files = mock(FileStorageService.class);
        when(files.readImage("202610", "owned.png")).thenReturn(new byte[]{1, 2});
        when(files.mimeOf("owned.png")).thenReturn("image/png");
        FileController images = new FileController(files, mock(DocumentParseService.class), mock(PdfRenderService.class), storage, json, access);
        assertEquals(403, images.getImage("202610", "owned.png", "share", null, request).getStatusCode().value());
        share.setPasswordHash(PasswordHasher.hash("secret"));
        assertEquals(true, controller.view("share").get("passwordRequired"));
        Map<String, Object> viewed = controller.viewWithPassword("share", Map.of("password", "secret"));
        String token = (String) viewed.get("accessToken");
        assertNotNull(token);
        share.setAccessCount(1);
        assertEquals(200, images.getImage("202610", "owned.png", "share", token, request).getStatusCode().value());
        assertEquals(403, images.getImage("202610", "other.png", "share", token, request).getStatusCode().value());
        assertEquals(false, controller.cloneShare("share", Map.of("password", "secret"), request).get("success"));
        assertEquals(true, controller.cloneShare("share", Map.of("accessToken", token), request).get("success"));
        share.setPasswordHash(PasswordHasher.hash("changed"));
        assertFalse(access.verifyToken(share, token));
        when(storage.getChatShareById("share")).thenReturn(null);
        assertEquals(403, images.getImage("202610", "owned.png", "share", token, request).getStatusCode().value());
    }

    /** 绕开阅读页直接复制也必须原子消费额度，并以原分享者权限转授资源。 */
    @Test
    void directCloneClaimsQuotaAndStopsOnConcurrentExhaustion() {
        when(shares.claimAccess(share)).thenReturn(false);
        assertEquals(false, controller.cloneShare("share", Map.of(), request).get("success"));
        verify(assets, never()).grantFrom(anyString(), anyString(), anyString());
        when(shares.claimAccess(share)).thenReturn(true);
        assertEquals(true, controller.cloneShare("share", Map.of(), request).get("success"));
        verify(assets).grantFrom(IMAGE, "owner", "visitor");
    }

    /** 新分享及旧的已标记脱敏快照都不能从标题、思考、历史版本或嵌套字段泄漏原文。 */
    @Test
    void sanitizedSnapshotCoversAllTextAndDoesNotMutateSource() throws Exception {
        var messages = List.of(Map.of("role", "user", "content", "secret@example.com",
                "reasoning_content", "13812345678", "versions", List.of(Map.of("content", "sk-abcdefghijklmnop")),
                "attachments", List.of(Map.of("name", "secret@example.com.txt"))));
        when(history.loadSingleChat("visitor", "chat")).thenReturn(Map.of("messages", messages));
        when(storage.addChatShare(any())).thenAnswer(call -> call.getArgument(0));
        ChatShare created = (ChatShare) controller.create(Map.of("chatId", "chat", "sanitized", true), request).get("data");
        String published = created.getTitle() + created.getSnapshotJson();
        assertFalse(published.contains("secret@example.com"));
        assertFalse(published.contains("13812345678"));
        assertFalse(published.contains("sk-abcdefghijklmnop"));
        assertEquals("secret@example.com", messages.getFirst().get("content"));
        share.setSanitized(true); share.setTitle("secret@example.com");
        share.setSnapshotJson(json.writeValueAsString(messages));
        String viewed = json.writeValueAsString(controller.view("share"));
        assertFalse(viewed.contains("secret@example.com"));
        assertFalse(viewed.contains("13812345678"));
        assertFalse(viewed.contains("sk-abcdefghijklmnop"));
    }

    /** 正文接口示例不当成附件，脱敏也不能替换合法资源 UUID 中的数字。 */
    @Test
    void textPathsAndNumericResourceIdsRemainUsable() throws Exception {
        String numericUrl = "/api/files/img/202610/12ab34cd56784abc8defa13812345678.png";
        when(assets.canShare(numericUrl, "visitor")).thenReturn(true);
        when(assets.grantFrom(numericUrl, "visitor", "visitor")).thenReturn(true);
        var messages = List.of(Map.of("role", "user", "content", "/api/files/img/202610/example.png 是接口示例",
                "images", List.of(numericUrl), "currentVersionId", "v13812345678",
                "versions", List.of(Map.of("versionId", "v13812345678", "content", "13812345678"))));
        when(history.loadSingleChat("visitor", "chat")).thenReturn(Map.of("messages", messages));
        when(storage.addChatShare(any())).thenAnswer(call -> call.getArgument(0));
        ChatShare created = (ChatShare) controller.create(Map.of("chatId", "chat", "sanitized", true), request).get("data");
        assertTrue(created.getSnapshotJson().contains(numericUrl));
        var snapshot = json.readTree(created.getSnapshotJson()).get(0);
        assertEquals(snapshot.get("currentVersionId").asText(), snapshot.at("/versions/0/versionId").asText());
        assertEquals("v13812345678", snapshot.get("currentVersionId").asText());
        assertFalse(snapshot.at("/versions/0/content").asText().contains("13812345678"));
        created.setId("share");
        when(storage.getChatShareById("share")).thenReturn(created);
        assertEquals(true, controller.view("share").get("success"));
        assertEquals(true, controller.cloneShare("share", Map.of(), request).get("success"));
    }
}
