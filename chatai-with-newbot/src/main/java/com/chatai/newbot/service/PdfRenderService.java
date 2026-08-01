package com.chatai.newbot.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 转图片服务：将上传的 PDF 逐页渲染为 JPEG 图片，交由多模态模型识别。
 * - 与文本解析（{@link DocumentParseService}）互补：PDF 走图片路线，可保留版式/图表/扫描件内容
 * - 渲染出的图片经 {@link FileStorageService#saveImageBytes} 落盘，返回访问 URL，
 *   前端将其作为普通图片附件加入消息，最终以 base64 传给模型（因此仅对多模态模型有效）
 * - 为控制 token 成本与请求体积，限制最大页数与渲染 DPI
 */
@Service
public class PdfRenderService {
    private static final Logger log = LoggerFactory.getLogger(PdfRenderService.class);

    /** PDF 文件大小上限（与前端及 multipart 限制协调，留出请求开销余量） */
    private static final long MAX_PDF_SIZE = 6L * 1024 * 1024;
    /** 单个 PDF 最多渲染页数：超出部分丢弃，避免图片过多导致 token 爆炸 */
    private static final int MAX_PAGES = 15;
    /** 渲染 DPI：兼顾清晰度与图片体积，144 对多数文档文字清晰可辨 */
    private static final float RENDER_DPI = 144f;

    private final FileStorageService fileStorageService;

    public PdfRenderService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /** 渲染结果：页面图片 URL 列表、PDF 原总页数、是否因超页数被截断 */
    public record RenderResult(List<String> images, int totalPages, boolean truncated) {}

    /**
     * 将 PDF 逐页渲染为 JPEG 并落盘
     * @param file 上传的 PDF 文件
     * @return 渲染结果（至少一页图片的 URL 列表）
     * @throws IllegalArgumentException 文件为空/超限/非 PDF/无有效页面
     * @throws Exception PDF 解析或渲染失败
     */
    public RenderResult render(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_PDF_SIZE) {
            throw new IllegalArgumentException("PDF 超过 6MB 限制");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持上传 PDF 文件");
        }

        byte[] bytes = file.getBytes();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages <= 0) {
                throw new IllegalArgumentException("PDF 没有可渲染的页面");
            }
            int renderPages = Math.min(totalPages, MAX_PAGES);
            PDFRenderer renderer = new PDFRenderer(doc);
            List<String> images = new ArrayList<>(renderPages);
            for (int p = 0; p < renderPages; p++) {
                BufferedImage img = renderer.renderImageWithDPI(p, RENDER_DPI, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", baos);
                String url = fileStorageService.saveImageBytes(baos.toByteArray(), "jpg");
                images.add(url);
            }
            log.info("PDF 渲染完成: {} 共{}页, 渲染{}页", name, totalPages, renderPages);
            return new RenderResult(images, totalPages, totalPages > renderPages);
        }
    }
}
