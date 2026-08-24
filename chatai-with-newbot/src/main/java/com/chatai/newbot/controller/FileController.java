package com.chatai.newbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatai.newbot.service.DocumentParseService;
import com.chatai.newbot.service.FileStorageService;
import com.chatai.newbot.service.PdfRenderService;
import com.chatai.newbot.service.StorageManager;
import com.chatai.newbot.model.ChatShare;
import com.chatai.newbot.model.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 文件上传/读取接口
 * - POST /api/upload/image：登录用户上传聊天图片，返回访问 URL
 * - POST /api/upload/document：登录用户上传附件文档（txt/doc/docx/xls等），
 *   服务端解析为纯文本后落盘，返回引用 URL（无需模型多模态能力）
 * - POST /api/upload/pdf：登录用户上传 PDF，服务端逐页渲染为图片，
 *   返回图片 URL 列表（交多模态模型识别）
 * - GET /api/files/img/{month}/{filename}：读取图片（拦截器已豁免，
 *   因 img 标签无法携带 Authorization 头，且分享页匿名查看也需访问；文件名为 UUID 不可枚举）
 */
@RestController
public class FileController {

    private final FileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final PdfRenderService pdfRenderService;
    private final StorageManager storageManager;
    private final ObjectMapper objectMapper;

    /** 注入文件处理、所有权校验与分享快照解析依赖。 */
    public FileController(FileStorageService fileStorageService,
                          DocumentParseService documentParseService,
                          PdfRenderService pdfRenderService,
                          StorageManager storageManager,
                          ObjectMapper objectMapper) {
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
        this.pdfRenderService = pdfRenderService;
        this.storageManager = storageManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传聊天图片（最大5MB，仅图片类型）
     */
    @PostMapping("/api/upload/image")
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file,
                                            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = fileStorageService.saveImage(file);
            User user = (User) request.getAttribute("currentUser");
            storageManager.registerFileAsset(url, user.getId(), "image");
            result.put("success", true);
            result.put("url", url);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "图片保存失败");
        }
        return result;
    }

    /**
     * 上传附件文档（最大10MB）：解析为纯文本后落盘，返回引用 URL 与文本字数
     */
    @PostMapping("/api/upload/document")
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String text = documentParseService.parse(file);
            String url = fileStorageService.saveDocumentText(text);
            User user = (User) request.getAttribute("currentUser");
            storageManager.registerFileAsset(url, user.getId(), "document");
            result.put("success", true);
            result.put("url", url);
            result.put("name", file.getOriginalFilename());
            result.put("chars", text.length());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "附件保存失败");
        }
        return result;
    }

    /**
     * 上传 PDF（最大6MB）：服务端逐页渲染为图片，返回图片 URL 列表，
     * 前端将其作为多模态图片附件发给模型。
     * 返回 Callable 使 Spring MVC 异步执行：逐页渲染（最多 15 页）为 CPU 密集操作，
     * 若在 Servlet 请求线程同步执行会长时间占用 Tomcat 工作线程，高并发上传可能耗尽线程池；
     * 异步化后 Servlet 线程立即释放，渲染在 MVC 异步任务线程上执行
     * （超时时长由 application.yml 的 spring.mvc.async.request-timeout 控制）。
     * 注意：MultipartFile 的内容在异步执行前已由容器缓存于内存/临时文件，异步读取安全。
     */
    @PostMapping("/api/upload/pdf")
    public Callable<Map<String, Object>> uploadPdf(@RequestParam("file") MultipartFile file,
                                                    HttpServletRequest request) {
        // 提前读取文件名（异步阶段请求上下文可能已失效）
        String originalName = file.getOriginalFilename();
        User current = (User) request.getAttribute("currentUser");
        String ownerUserId = current.getId();
        return () -> {
            Map<String, Object> result = new HashMap<>();
            try {
                PdfRenderService.RenderResult r = pdfRenderService.render(file);
                for (String url : r.images()) {
                    storageManager.registerFileAsset(url, ownerUserId, "pdf-page");
                }
                result.put("success", true);
                result.put("images", r.images());
                result.put("name", originalName);
                result.put("pages", r.images().size());
                result.put("totalPages", r.totalPages());
                result.put("truncated", r.truncated());
            } catch (IllegalArgumentException e) {
                result.put("success", false);
                result.put("message", e.getMessage());
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "PDF 转换失败");
            }
            return result;
        };
    }

    /**
     * 读取已上传的图片（浏览器可长缓存：文件名唯一且内容不变）
     */
    @GetMapping("/api/files/img/{month}/{filename:.+}")
    public ResponseEntity<byte[]> getImage(@PathVariable String month, @PathVariable String filename,
                                            @RequestParam(required = false) String shareId,
                                            HttpServletRequest request) {
        String url = FileStorageService.IMG_URL_PREFIX + month + "/" + filename;
        User user = resolveRequestUser(request);
        boolean ownerAllowed = storageManager.canAccessFileAsset(url,
                user == null ? null : user.getId(), user != null && user.isAdmin());
        if (!ownerAllowed && !isAllowedByShare(url, shareId)) {
            return ResponseEntity.status(403).build();
        }
        byte[] bytes = fileStorageService.readImage(month, filename);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileStorageService.mimeOf(filename)))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate());
        // 存量 svg 兜底：新上传已禁止 SVG，但历史文件仍可能存在。SVG 顶级导航打开会执行
        // 内嵌脚本（存储型 XSS），故强制以附件下载而非内联渲染，并附加沙箱 CSP 双重防护
        if (fileStorageService.isSvg(filename)) {
            builder.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Security-Policy", "sandbox");
        }
        return builder.body(bytes);
    }

    /**
     * 从 Authorization/Cookie 中解析当前用户；图片路由本身保持匿名可达以支持分享页。
     */
    private User resolveRequestUser(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        if ((token == null || token.isBlank()) && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("token".equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        return storageManager.getUserByToken(token);
    }

    /**
     * 校验匿名分享是否确实包含目标图片 URL，防止仅凭文件名跨分享读取其他资源。
     */
    private boolean isAllowedByShare(String url, String shareId) {
        if (shareId == null || shareId.isBlank()) return false;
        ChatShare share = storageManager.getChatShareById(shareId);
        if (share == null || share.getSnapshotJson() == null) return false;
        if (share.getExpiresAt() != null && !share.getExpiresAt().isBlank()) {
            try {
                if (java.time.LocalDateTime.now().isAfter(java.time.LocalDateTime.parse(
                        share.getExpiresAt(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))) {
                    return false;
                }
            } catch (Exception ignore) { }
        }
        try {
            JsonNode messages = objectMapper.readTree(share.getSnapshotJson());
            if (!messages.isArray()) return false;
            for (JsonNode message : messages) {
                JsonNode images = message.get("images");
                if (images == null || !images.isArray()) continue;
                for (JsonNode image : images) {
                    if (image.isTextual() && url.equals(image.asText())) return true;
                }
            }
            return false;
        } catch (Exception ignore) {
            return false;
        }
    }
}
