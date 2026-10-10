package com.chatai.newbot.controller;

import com.chatai.newbot.config.MaintenanceFilter;
import com.chatai.newbot.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 匿名端点和管理端点必须共享恢复维护边界。 */
class MaintenanceFilterTest {
    /** 恢复过程中或恢复故障后，所有 API 都返回 503，不进入下游读写逻辑。 */
    @Test
    void maintenanceBlocksEveryApiIncludingAnonymousRoutes() throws Exception {
        BackupService backups = mock(BackupService.class);
        when(backups.isRestoring()).thenReturn(true);
        MaintenanceFilter filter = new MaintenanceFilter(backups);
        for (String route : new String[]{"/api/auth/login", "/api/auth/register", "/api/auth/2fa/verify-login",
                "/api/share/view/id", "/api/files/img/202610/x.png", "/api/admin/backups", "/api/chat"}) {
            var request = new MockHttpServletRequest("POST", route);
            var response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reached.set(true));
            assertEquals(503, response.getStatus(), route);
            assertFalse(reached.get(), route);
        }
    }
}
