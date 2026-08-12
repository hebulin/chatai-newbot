package com.chatai.newbot.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    /** 附件文档解析文本的引用 URL 前缀（仅作消息内部引用，未对外提供 HTTP 读取） */
    public static final String DOC_URL_PREFIX = "/api/files/doc/";
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
        // SVG 可内嵌 <script>/事件处理器，以 image/svg+xml 顶级导航打开时浏览器会当作
        // HTML 文档执行脚本（存储型 XSS）；聊天/分享页并不需要 SVG，直接拒绝上传
        if (contentType.toLowerCase().contains("svg")) {
            throw new IllegalArgumentException("出于安全考虑，不支持上传 SVG 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片超过5MB限制");
        }
        // 魔数（magic bytes）校验：Content-Type 可被客户端伪造，读取文件头判定真实图片格式，
        // 防止把非图片文件（如脚本/可执行文件）伪装成图片上传落盘
        byte[] header = new byte[12];
        int headerLen = 0;
        try (java.io.InputStream is = file.getInputStream()) {
            int read;
            while (headerLen < header.length && (read = is.read(header, headerLen, header.length - headerLen)) > 0) {
                headerLen += read;
            }
        }
        if (!isKnownImageMagic(header, headerLen)) {
            throw new IllegalArgumentException("文件内容不是有效的图片格式");
        }
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path monthDir = uploadDir.resolve(month);
        Files.createDirectories(monthDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extOf(contentType);
        Files.write(monthDir.resolve(filename), file.getBytes());
        return IMG_URL_PREFIX + month + "/" + filename;
    }

    /**
     * 校验文件头魔数是否为已知图片格式（JPEG/PNG/GIF/WebP/BMP）。
     * Content-Type 可伪造，以真实字节签名判定文件类型，防止恶意文件伪装上传。
     * @param header 文件头字节（至少 12 字节，不足时为实际长度）
     * @param len 实际读取到的文件头长度
     * @return true=已知图片格式
     */
    private boolean isKnownImageMagic(byte[] header, int len) {
        if (len < 4) return false;
        // JPEG: FF D8 FF
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) return true;
        // PNG: 89 50 4E 47
        if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) return true;
        // GIF: "GIF8"
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') return true;
        // BMP: "BM"
        if (header[0] == 'B' && header[1] == 'M') return true;
        // WebP: "RIFF" .... "WEBP"（需完整 12 字节头）
        if (len >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return true;
        }
        return false;
    }

    /**
     * 保存服务端生成的图片字节（如 PDF 渲染出的页面图），落盘规则与 {@link #saveImage} 一致
     * @param bytes 图片字节
     * @param ext 扩展名（不含点，如 jpg/png）
     * @return 图片访问 URL（/api/files/img/{yyyyMM}/{filename}）
     * @throws IOException 落盘失败
     */
    public String saveImageBytes(byte[] bytes, String ext) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        String safeExt = (ext == null || !SAFE_NAME.matcher(ext).matches()) ? "png" : ext.toLowerCase();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path monthDir = uploadDir.resolve(month);
        Files.createDirectories(monthDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + safeExt;
        Files.write(monthDir.resolve(filename), bytes);
        return IMG_URL_PREFIX + month + "/" + filename;
    }

    /**
     * 读取已上传的图片内容
     * @param month 月份目录（yyyyMM）
     * @param filename 文件名
     * @return 图片字节；不存在或参数非法返回 null
     */
    public byte[] readImage(String month, String filename) {
        if (!isSafeSegment(month) || !isSafeSegment(filename)) {
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
     * 保存附件文档解析后的纯文本（落盘到 data/uploads/doc/{yyyyMM}/{uuid}.txt）
     * 消息中仅保存引用 URL，模型调用时通过 {@link #readDocumentText(String)} 读回，
     * 避免解析内容内嵌会话历史导致体积膨胀
     * @param text 解析出的纯文本
     * @return 引用 URL（/api/files/doc/{yyyyMM}/{filename}）
     */
    public String saveDocumentText(String text) throws IOException {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        Path monthDir = uploadDir.resolve("doc").resolve(month);
        Files.createDirectories(monthDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + ".txt";
        Files.writeString(monthDir.resolve(filename), text, StandardCharsets.UTF_8);
        return DOC_URL_PREFIX + month + "/" + filename;
    }

    /**
     * 根据引用 URL 读取附件文档的解析文本（供模型 API 调用时合并进消息内容）
     * @param url 引用 URL（/api/files/doc/{yyyyMM}/{filename}）
     * @return 解析文本；引用非法或文件不存在返回 null
     */
    public String readDocumentText(String url) {
        if (url == null || !url.startsWith(DOC_URL_PREFIX)) {
            return null;
        }
        String rest = url.substring(DOC_URL_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String month = rest.substring(0, slash);
        String filename = rest.substring(slash + 1);
        if (!isSafeSegment(month) || !isSafeSegment(filename)) {
            return null;
        }
        try {
            Path path = uploadDir.resolve("doc").resolve(month).resolve(filename);
            if (!Files.exists(path)) {
                log.warn("消息引用的附件解析文本不存在: {}", url);
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取附件解析文本失败: {}/{}", month, filename, e);
            return null;
        }
    }

    /**
     * 路径段安全校验：仅允许字母数字点横线，且显式拒绝 "."/".." 与任何 ".." 穿越序列。
     * 正则本身允许 ".."（点在字符类中），故必须额外拦截，防止目录穿越读取上级敏感文件。
     */
    private static boolean isSafeSegment(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            return false;
        }
        return !name.equals(".") && !name.equals("..") && !name.contains("..");
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
            default -> "png";
        };
    }

    /**
     * 是否为 SVG 文件：读取侧据此对存量 svg 强制以附件下载，避免内嵌脚本被当作 HTML 执行。
     */
    public boolean isSvg(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".svg");
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
