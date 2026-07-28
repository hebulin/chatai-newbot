package com.chatai.newbot.controller;

import com.chatai.newbot.service.FileStorageService;
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
 * - GET /api/files/img/{month}/{filename}：读取图片（拦截器已豁免，
 *   因 img 标签无法携带 Authorization 头，且分享页匿名查看也需访问；文件名为 UUID 不可枚举）
 */
@RestController
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
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
     * 读取已上传的图片（浏览器可长缓存：文件名唯一且内容不变）
     */
    @GetMapping("/api/files/img/{month}/{filename:.+}")
    public ResponseEntity<byte[]> getImage(@PathVariable String month, @PathVariable String filename) {
        byte[] bytes = fileStorageService.readImage(month, filename);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileStorageService.mimeOf(filename)))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(bytes);
    }
}
