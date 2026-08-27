package com.chatai.newbot.controller;

import com.chatai.newbot.config.AdminSupport;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.BackupService;
import com.chatai.newbot.service.StorageManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统完整备份与恢复管理接口（仅管理员）。
 * 备份覆盖数据库一致性快照、上传资源与解密密钥；恢复为高风险操作，
 * 需重新输入当前管理员密码确认，全程记录审计日志，恢复期间系统进入维护状态。
 */
@RestController
@RequestMapping("/api/admin/backups")
public class AdminBackupController {
    private static final Logger log = LoggerFactory.getLogger(AdminBackupController.class);

    private final BackupService backupService;
    private final StorageManager storageManager;
    private final AdminSupport adminSupport;

    public AdminBackupController(BackupService backupService, StorageManager storageManager,
                                 AdminSupport adminSupport) {
        this.backupService = backupService;
        this.storageManager = storageManager;
        this.adminSupport = adminSupport;
    }

    /** 列出全部备份文件（名称/大小/时间） */
    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", backupService.listBackups());
        result.put("autoBackupEnabled", isAutoBackupEnabled());
        return result;
    }

    /** 立即创建一份完整备份 */
    @PostMapping
    public Map<String, Object> create(HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> info = backupService.createBackup();
            adminSupport.audit(request, "创建系统备份", String.valueOf(info.get("name")));
            result.put("success", true);
            result.put("data", info);
        } catch (Exception e) {
            log.error("创建系统备份失败", e);
            result.put("success", false);
            result.put("message", "备份失败: " + e.getMessage());
        }
        return result;
    }

    /** 下载指定备份文件（仅管理员鉴权访问，备份不经公开静态目录暴露） */
    @GetMapping("/download/{name}")
    public Object download(@PathVariable String name, HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        try {
            Path path = backupService.resolveBackup(name);
            Resource resource = new FileSystemResource(path);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            return Map.of("success", false, "message", "备份文件不存在或不可读");
        }
    }

    /** 删除指定备份文件 */
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name, HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        try {
            boolean deleted = backupService.deleteBackup(name);
            if (deleted) {
                adminSupport.audit(request, "删除系统备份", name);
            }
            result.put("success", deleted);
            if (!deleted) result.put("message", "备份文件不存在");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 从服务器已有备份恢复（高风险：需重新输入当前管理员密码确认）。
     * 请求体: { "name": "backup-xxx.zip", "password": "当前管理员密码" }
     * 恢复流程：格式/完整性/路径安全/密钥匹配校验 → 恢复前备份 → 维护状态导入 → 失效全部登录态
     */
    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        User admin = (User) request.getAttribute("currentUser");
        String name = body != null && body.get("name") != null ? String.valueOf(body.get("name")) : "";
        String password = body != null && body.get("password") != null ? String.valueOf(body.get("password")) : "";
        // 高风险操作重新认证：必须输入当前管理员密码
        if (storageManager.authenticate(admin.getUsername(), password) == null) {
            result.put("success", false);
            result.put("message", "管理员密码错误，恢复已取消");
            return result;
        }
        try {
            Path path = backupService.resolveBackup(name);
            Map<String, Object> summary = backupService.restoreBackup(path);
            adminSupport.audit(request, "恢复系统备份", name);
            result.put("success", true);
            result.put("data", summary);
            result.put("message", "恢复完成，请重新登录");
        } catch (Exception e) {
            log.error("恢复系统备份失败: {}", name, e);
            adminSupport.audit(request, "恢复系统备份失败", name + ": " + e.getMessage());
            result.put("success", false);
            result.put("message", "恢复失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 上传备份文件并恢复（高风险：需重新输入当前管理员密码确认）。
     * 上传文件先落临时目录校验，不进入备份目录，恢复完成后删除。
     */
    @PostMapping("/restore-upload")
    public Map<String, Object> restoreUpload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("password") String password,
                                              HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        User admin = (User) request.getAttribute("currentUser");
        if (storageManager.authenticate(admin.getUsername(), password) == null) {
            result.put("success", false);
            result.put("message", "管理员密码错误，恢复已取消");
            return result;
        }
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "请选择备份文件");
            return result;
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("chatai-restore-upload", ".zip");
            file.transferTo(tempFile);
            Map<String, Object> summary = backupService.restoreBackup(tempFile);
            adminSupport.audit(request, "上传并恢复系统备份", file.getOriginalFilename());
            result.put("success", true);
            result.put("data", summary);
            result.put("message", "恢复完成，请重新登录");
        } catch (Exception e) {
            log.error("上传恢复系统备份失败", e);
            adminSupport.audit(request, "恢复系统备份失败", "upload: " + e.getMessage());
            result.put("success", false);
            result.put("message", "恢复失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignore) { }
            }
        }
        return result;
    }

    /** 读取/更新定时备份开关（每日凌晨 03:00 自动备份，保留最近 5 份） */
    @PutMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        adminSupport.requireAdmin(request);
        Map<String, Object> result = new HashMap<>();
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        storageManager.setSetting("backup_auto_enabled", enabled ? "true" : "false");
        adminSupport.audit(request, "定时备份设置", enabled ? "开启" : "关闭");
        result.put("success", true);
        result.put("autoBackupEnabled", enabled);
        return result;
    }

    /** 定时备份是否开启（t_setting: backup_auto_enabled，缺省开启） */
    private boolean isAutoBackupEnabled() {
        String val = storageManager.getSetting("backup_auto_enabled");
        return val == null || !"false".equals(val);
    }
}
