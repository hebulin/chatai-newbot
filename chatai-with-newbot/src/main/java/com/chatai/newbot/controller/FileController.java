package com.chatai.newbot.controller;

import com.chatai.newbot.service.DocumentParseService;
import com.chatai.newbot.service.FileStorageService;
import com.chatai.newbot.service.PdfRenderService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
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

    public FileController(FileStorageService fileStorageService,
                          DocumentParseService documentParseService,
                          PdfRenderService pdfRenderService) {
        this.fileStorageService = fileStorageService;
        this.documentParseService = documentParseService;
        this.pdfRenderService = pdfRenderService;
    }

    /**
     * 上传聊天图片（最大5MB，仅图片类型）
     */
    @PostMapping("/api/upload/image")
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = fileStorageService.saveImage(file);
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
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            String text = documentParseService.parse(file);
            String url = fileStorageService.saveDocumentText(text);
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
     * 前端将其作为多模态图片附件发给模型
     */
    @PostMapping("/api/upload/pdf")
    public Map<String, Object> uploadPdf(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            PdfRenderService.RenderResult r = pdfRenderService.render(file);
            result.put("success", true);
            result.put("images", r.images());
            result.put("name", file.getOriginalFilename());
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
    }

    /**
     * 读取已上传的图片（浏览器可长缓存：文件名唯一且内容不变）
     */
    @GetMapping("/api/files/img/{month}/{filename:.+}")
    public ResponseEntity<byte[]> getImage(@PathVariable String month, @PathVariable String filename) {
        byte[] bytes = fileStorageService.readImage(month, filename);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileStorageService.mimeOf(filename)))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic());
        // 存量 svg 兜底：新上传已禁止 SVG，但历史文件仍可能存在。SVG 顶级导航打开会执行
        // 内嵌脚本（存储型 XSS），故强制以附件下载而非内联渲染，并附加沙箱 CSP 双重防护
        if (fileStorageService.isSvg(filename)) {
            builder.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Security-Policy", "sandbox");
        }
        return builder.body(bytes);
    }
}
