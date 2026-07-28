package com.chatai.newbot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 上传文件存储服务：聊天图片从 base64 内嵌改为服务端文件存储
 * - 文件落盘在 data/uploads/{yyyyMM}/{uuid}.{ext}，按月分目录便于归档清理
 * - 消息中仅保存访问 URL（/api/files/img/{yyyyMM}/{filename}），大幅减小会话历史体积
 * - 调用模型 API 前通过 {@link #toDataUrl(String)} 将本地 URL 还原为 base64 data URL
 */
@Service
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    /** 图片访问 URL 前缀（与 FileController 的映射保持一致） */
    public static final String IMG_URL_PREFIX = "/api/files/img/";
    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024; // 5MB，与前端限制一致
    /** 合法文件名/月份目录：仅字母数字点横线，防目录穿越 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private Path uploadDir;

    /**
     * 初始化：确保上传根目录存在
     */
    @PostConstruct
    public void init() {
        try {
            String userDir = System.getProperty("user.dir");
            this.uploadDir = Paths.get(userDir, "data", "uploads");
            Files.createDirectories(uploadDir);
            log.info("上传文件存储目录: {}", uploadDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("初始化上传文件存储目录失败", e);
        }
    }

    /**
     * 保存上传的图片文件
     * @param file 上传的图片
     * @return 图片访问 URL（/api/files/img/{yyyyMM}/{filename}）
     * @throws IllegalArgumentException 文件类型/大小校验失败
     * @throws IOException 落盘失败
     */
    public String saveImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("仅支持上传图片文件");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片超过5MB限制");
        }
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path monthDir = uploadDir.resolve(month);
        Files.createDirectories(monthDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extOf(contentType);
        Files.write(monthDir.resolve(filename), file.getBytes());
        return IMG_URL_PREFIX + month + "/" + filename;
    }

    /**
     * 读取已上传的图片内容
     * @param month 月份目录（yyyyMM）
     * @param filename 文件名
     * @return 图片字节；不存在或参数非法返回 null
     */
    public byte[] readImage(String month, String filename) {
        if (!SAFE_NAME.matcher(month).matches() || !SAFE_NAME.matcher(filename).matches()) {
            return null;
        }
        try {
            Path path = uploadDir.resolve(month).resolve(filename);
            if (!Files.exists(path)) return null;
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.error("读取上传图片失败: {}/{}", month, filename, e);
            return null;
        }
    }

    /**
     * 将消息中的图片引用统一还原为 base64 data URL（供模型 API 调用）
     * - 本地上传 URL（/api/files/img/...）→ 读文件转 data URL
     * - data: 开头的存量 base64 → 原样返回
     * @param image 图片引用（URL 或 data URL）
     * @return base64 data URL；本地文件不存在时返回 null
     */
    public String toDataUrl(String image) {
        if (image == null || !image.startsWith(IMG_URL_PREFIX)) {
            return image;
        }
        String rest = image.substring(IMG_URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String month = rest.substring(0, slash);
        String filename = rest.substring(slash + 1);
        byte[] bytes = readImage(month, filename);
        if (bytes == null) {
            log.warn("消息引用的上传图片不存在: {}", image);
            return null;
        }
        return "data:" + mimeOf(filename) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 根据 Content-Type 推断文件扩展名
     */
    private String extOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            case "image/svg+xml" -> "svg";
            default -> "png";
        };
    }

    /**
     * 根据文件扩展名推断 MIME 类型
     */
    public String mimeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        return "image/png";
    }
}
