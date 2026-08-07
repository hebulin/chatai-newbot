package com.chatai.newbot.config;

import com.chatai.newbot.exception.ForbiddenException;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 管理后台控制器公共支持：管理员身份校验与审计日志记录。
 * 后台控制器已按模块拆分（模型/用户/用量/设置/公告/审计/分享），
 * 各控制器通过本组件复用这两段通用逻辑，避免重复代码。
 */
@Component
public class AdminSupport {

    private final AuditLogService auditLogService;

    public AdminSupport(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 级联校验管理员身份（拦截器已统一拦截 /api/admin，此处为纵深防御），
     * 不通过时抛出 ForbiddenException，由全局异常处理器统一返回 403
     * @param request 当前请求
     */
    public void requireAdmin(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        if (user == null || !user.isAdmin()) {
            throw new ForbiddenException();
        }
    }

    /**
     * 记录管理员操作审计日志（操作人取当前登录管理员）
     * @param request 当前请求
     * @param action 操作类型（如 model.add）
     * @param detail 操作详情
     */
    public void audit(HttpServletRequest request, String action, String detail) {
        User user = (User) request.getAttribute("currentUser");
        auditLogService.record(user == null ? null : user.getId(),
                user == null ? null : user.getUsername(), action, detail, IpUtils.getClientIp(request));
    }
}
