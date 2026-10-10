package com.chatai.newbot.controller;

import com.chatai.newbot.config.AdminSupport;
import com.chatai.newbot.exception.GlobalExceptionHandler;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.BackupService;
import com.chatai.newbot.service.StorageManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 备份原始字节上传的路由、认证、大小限制和临时文件生命周期。 */
class BackupUploadTest {
    /** 7 MiB 备份无需提高普通 multipart 上限，并支持非 ASCII 管理员密码。 */
    @Test
    void acceptsBackupAboveChatLimitAndRemovesTemporaryFile() throws Exception {
        BackupService backups = mock(BackupService.class);
        StorageManager storage = mock(StorageManager.class);
        AdminSupport admin = mock(AdminSupport.class);
        User user = new User(); user.setUsername("admin");
        when(storage.authenticate("admin", "密码test123")).thenReturn(user);
        AtomicReference<Path> temporary = new AtomicReference<>();
        when(backups.restoreBackup(any())).thenAnswer(call -> {
            Path path = call.getArgument(0); temporary.set(path);
            assertEquals(7 * 1024 * 1024, Files.size(path));
            return Map.of("tables", Map.of());
        });
        var controller = new AdminBackupController(backups, storage, admin);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        String password = Base64.getEncoder().encodeToString("密码test123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(post("/api/admin/backups/restore-upload").contentType("application/zip")
                .header("X-Restore-Password", password).requestAttr("currentUser", user)
                .content(new byte[7 * 1024 * 1024]))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertFalse(Files.exists(temporary.get()));
        ReflectionTestUtils.setField(controller, "maxUploadBytes", 1024L);
        mvc.perform(post("/api/admin/backups/restore-upload").contentType("application/zip")
                .header("X-Restore-Password", password).requestAttr("currentUser", user).content(new byte[2048]))
                .andExpect(status().is(413));
        verify(backups, times(1)).restoreBackup(any());
    }
}
