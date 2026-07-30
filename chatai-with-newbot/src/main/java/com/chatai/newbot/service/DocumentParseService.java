package com.chatai.newbot.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 聊天附件文档解析服务：将上传的文本类文档统一提取为纯文本
 * - 纯文本类（txt/log/md/csv/json/xml 等）：按 UTF-8 优先、GBK 兜底解码
 * - Word（doc/docx）：Apache POI 提取正文文本
 * - 表格（xls/xlsx/csv）：逐行提取单元格，制表符分隔，保留行列结构供模型理解
 * 解析结果为纯文本后交由 {@link FileStorageService} 落盘，模型调用时直接读取，
 * 无需模型具备多模态能力，也避免每轮对话重复解析原始文档。
 */
@Service
public class DocumentParseService {
    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    /** 附件文档大小上限（3MB，application.yml 的 multipart 限制需大于该值） */
    public static final long MAX_DOC_SIZE = 3L * 1024 * 1024;
    /** 提取文本最大字符数：超出截断，避免撑爆模型上下文与会话请求体 */
    private static final int MAX_TEXT_CHARS = 60_000;

    /** 支持的扩展名（小写） */
    private static final Set<String> SUPPORTED_EXTS = Set.of(
            "txt", "log", "md", "markdown", "csv", "json", "xml",
            "yml", "yaml", "properties", "doc", "docx", "xls", "xlsx"
    );

    /**
     * 校验并解析上传的文档，返回提取出的纯文本
     * @throws IllegalArgumentException 文件为空/类型不支持/超限/解析失败
     */
    public String parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        if (file.getSize() > MAX_DOC_SIZE) {
            throw new IllegalArgumentException("附件超过3MB限制");
        }
        String ext = extOf(file.getOriginalFilename());
        if (!SUPPORTED_EXTS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + (ext.isEmpty() ? "(无扩展名)" : ext));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取上传文件失败");
        }
        String text;
        try {
            text = switch (ext) {
                case "doc" -> extractDoc(bytes);
                case "docx" -> extractDocx(bytes);
                case "xls", "xlsx" -> extractWorkbook(bytes);
                default -> decodePlainText(bytes);
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("附件文档解析失败: {}", file.getOriginalFilename(), e);
            throw new IllegalArgumentException("文档解析失败，请确认文件未加密且格式正确");
        }
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("未能从文档中提取到文本内容");
        }
        return truncate(text.trim());
    }

    /**
     * 提取文件扩展名（小写，无扩展名返回空串）
     */
    public String extOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * 旧版 Word 二进制格式（.doc）
     */
    private String extractDoc(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * OOXML Word 格式（.docx）
     */
    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /**
     * Excel 表格（.xls/.xlsx）：逐 sheet 逐行提取，单元格以制表符分隔
     */
    private String extractWorkbook(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (workbook.getNumberOfSheets() > 1) {
                    sb.append("### 工作表: ").append(sheet.getSheetName()).append('\n');
                }
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    short lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        if (c > 0) line.append('\t');
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            line.append(formatter.formatCellValue(cell));
                        }
                    }
                    // 跳过整行皆空的行，压缩无效内容
                    if (!line.toString().trim().isEmpty()) {
                        sb.append(line).append('\n');
                    }
                    if (sb.length() > MAX_TEXT_CHARS) return sb.toString();
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 纯文本解码：UTF-8 严格解码优先，失败回退 GBK（兼容 Windows 中文环境导出的 txt/csv/log）
     */
    private String decodePlainText(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /**
     * 超长文本截断并附截断说明，告知模型内容不完整
     */
    private String truncate(String text) {
        if (text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS) + "\n\n（注：文档过长，以上内容已截断，仅保留前 " + MAX_TEXT_CHARS + " 个字符）";
    }
}
