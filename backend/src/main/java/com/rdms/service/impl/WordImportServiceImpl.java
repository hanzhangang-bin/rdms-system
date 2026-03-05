package com.rdms.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.rdms.dto.ImportResponse;
import com.rdms.entity.DocCatalog;
import com.rdms.entity.DocContent;
import com.rdms.service.WordImportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WordImportServiceImpl implements WordImportService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("doc", "docx", "wps");
    private static final Set<String> SUPPORTED_DOC_TYPES = Set.of("REQUIREMENT", "DESIGN", "TESTCASE");

    private static final Pattern DECIMAL_TITLE_PATTERN = Pattern.compile("^((?:\\d+\\.)*\\d+)\\s+(.+)$");
    private static final Pattern NUMERIC_TITLE_PATTERN = Pattern.compile("^(\\d+)[、.．]\\s*(.+)$");
    private static final Pattern CN_SECTION_PATTERN = Pattern.compile("^第([一二三四五六七八九十百千万]+)([章节部分])\\s+(.+)$");
    private static final Pattern BULLET_LEVEL_PATTERN = Pattern.compile("^([（(]?[一二三四五六七八九十]+[）)])\\s*(.+)$");
    private static final Pattern TOC_FIELD_PATTERN = Pattern.compile(
            "HYPERLINK\\s+\\\\l\\s+_Toc\\d+\\s+(.+?)\\s+PAGEREF\\s+_Toc\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PAGE_NO_PATTERN = Pattern.compile("^(.+?)\\s+(\\d{1,4})$");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResponse importWord(MultipartFile file, String documentGroupId, String docType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String normalizedDocType = normalizeDocType(docType);
        String extension = validateAndGetExtension(file.getOriginalFilename());
        String groupId = StringUtils.hasText(documentGroupId) ? documentGroupId : UUID.randomUUID().toString();

        ParseContext context = new ParseContext(groupId, normalizedDocType);

        try {
            List<RawParagraph> paragraphs = readParagraphs(file.getBytes(), extension);
            for (RawParagraph paragraph : paragraphs) {
                processParagraph(context, paragraph);
            }
        } catch (IOException e) {
            log.error("导入Word失败", e);
            throw new IllegalStateException("解析Word文档失败", e);
        }

        if (!context.getContents().isEmpty()) {
            Db.saveBatch(context.getContents());
        }

        return ImportResponse.builder()
                .documentGroupId(groupId)
                .docType(normalizedDocType)
                .catalogCount(context.getCatalogs().size())
                .build();
    }

    private void processParagraph(ParseContext context, RawParagraph paragraph) {
        String text = cleanText(paragraph.getText());
        if (text.isEmpty()) {
            return;
        }

        HeadingInfo heading = parseHeading(paragraph, text);
        if (heading != null) {
            while (context.getTitlePath().size() >= heading.getLevel()) {
                context.getTitlePath().pollLast();
            }
            context.getTitlePath().addLast(heading.getTitle());

            DocCatalog catalog = new DocCatalog();
            catalog.setDocumentGroupId(context.getGroupId());
            catalog.setDocType(context.getDocType());
            catalog.setCatalogNo(heading.getCatalogNo());
            catalog.setTitle(heading.getTitle());
            catalog.setCatalogLevel(heading.getLevel());
            catalog.setParentId(heading.getLevel() > 1 ? context.getLatestCatalogByLevel().get(heading.getLevel() - 1) : null);
            catalog.setFullPath(String.join(" / ", context.getTitlePath()));

            Db.save(catalog);
            context.getCatalogs().add(catalog);
            context.getLatestCatalogByLevel().put(heading.getLevel(), catalog.getId());
            clearDeeperLevels(context.getLatestCatalogByLevel(), heading.getLevel());

            DocContent content = new DocContent();
            content.setCatalogId(catalog.getId());
            content.setDocumentGroupId(context.getGroupId());
            content.setContentText("");
            context.getContents().add(content);
            context.setCurrentContent(content);
            return;
        }

        if (context.getCurrentContent() != null) {
            String old = context.getCurrentContent().getContentText();
            context.getCurrentContent().setContentText((old == null || old.isEmpty()) ? text : old + "\n" + text);
        }
    }

    private String normalizeDocType(String docType) {
        if (!StringUtils.hasText(docType)) {
            throw new IllegalArgumentException("docType 不能为空");
        }
        String normalized = docType.trim().toUpperCase();
        if (!SUPPORTED_DOC_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("docType 仅支持 REQUIREMENT/DESIGN/TESTCASE");
        }
        return normalized;
    }

    private String validateAndGetExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            throw new IllegalArgumentException("文件名无效");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("仅支持 doc/docx/wps 格式");
        }
        return ext;
    }

    private List<RawParagraph> readParagraphs(byte[] bytes, String extension) throws IOException {
        if ("docx".equals(extension)) {
            return readDocxParagraphs(bytes);
        }
        if ("doc".equals(extension)) {
            return readDocParagraphs(bytes);
        }
        try {
            return readDocParagraphs(bytes);
        } catch (Exception ignore) {
            return readDocxParagraphs(bytes);
        }
    }

    private List<RawParagraph> readDocxParagraphs(byte[] bytes) throws IOException {
        List<RawParagraph> result = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                int levelHint = parseLevelHint(paragraph);
                result.add(new RawParagraph(paragraph.getStyle(), paragraph.getText(), levelHint));
            }
        }
        return result;
    }

    private int parseLevelHint(XWPFParagraph paragraph) {
        BigInteger ilvl = paragraph.getNumIlvl();
        if (ilvl != null) {
            return ilvl.intValue() + 1;
        }
        if (paragraph.getCTP() != null
                && paragraph.getCTP().getPPr() != null
                && paragraph.getCTP().getPPr().getOutlineLvl() != null) {
            return paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue() + 1;
        }
        return 0;
    }

    private List<RawParagraph> readDocParagraphs(byte[] bytes) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            List<RawParagraph> result = new ArrayList<>();
            for (String p : extractor.getParagraphText()) {
                result.add(new RawParagraph(null, p, 0));
            }
            return result;
        }
    }

    private HeadingInfo parseHeading(RawParagraph paragraph, String text) {
        String headingText = preprocessHeadingText(text);

        Integer styleLevel = parseHeadingLevelByStyle(paragraph.getStyle());
        if (styleLevel != null) {
            HeadingInfo byNo = parseByNumbering(headingText, styleLevel);
            return byNo != null ? byNo : new HeadingInfo(String.valueOf(styleLevel), headingText, styleLevel);
        }

        HeadingInfo byDecimal = parseByDecimal(headingText);
        if (byDecimal != null) {
            return byDecimal;
        }

        HeadingInfo byNumeric = parseByNumeric(headingText);
        if (byNumeric != null) {
            return byNumeric;
        }

        HeadingInfo byChineseSection = parseByChineseSection(headingText);
        if (byChineseSection != null) {
            return byChineseSection;
        }

        Matcher bulletMatcher = BULLET_LEVEL_PATTERN.matcher(headingText);
        if (bulletMatcher.matches()) {
            return new HeadingInfo(bulletMatcher.group(1), bulletMatcher.group(2), Math.max(paragraph.getLevelHint(), 3));
        }

        if (paragraph.getLevelHint() > 0 && isLikelyTitle(headingText)) {
            return new HeadingInfo(String.valueOf(paragraph.getLevelHint()), headingText, paragraph.getLevelHint());
        }
        return null;
    }

    private HeadingInfo parseByNumbering(String text, int fallbackLevel) {
        HeadingInfo byDecimal = parseByDecimal(text);
        if (byDecimal != null) {
            return byDecimal;
        }
        HeadingInfo byNumeric = parseByNumeric(text);
        if (byNumeric != null) {
            return byNumeric;
        }
        HeadingInfo byChineseSection = parseByChineseSection(text);
        if (byChineseSection != null) {
            return byChineseSection;
        }
        return new HeadingInfo(String.valueOf(fallbackLevel), text, fallbackLevel);
    }

    private HeadingInfo parseByDecimal(String text) {
        Matcher matcher = DECIMAL_TITLE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String number = matcher.group(1);
        String title = matcher.group(2);
        int level = number.split("\\.").length;
        return new HeadingInfo(number, title, level);
    }

    private HeadingInfo parseByNumeric(String text) {
        Matcher matcher = NUMERIC_TITLE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        return new HeadingInfo(matcher.group(1), matcher.group(2), 1);
    }

    private HeadingInfo parseByChineseSection(String text) {
        Matcher matcher = CN_SECTION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String no = "第" + matcher.group(1) + matcher.group(2);
        String title = matcher.group(3);
        int level = "章".equals(matcher.group(2)) ? 1 : 2;
        return new HeadingInfo(no, title, level);
    }

    private Integer parseHeadingLevelByStyle(String style) {
        if (!StringUtils.hasText(style)) {
            return null;
        }
        String normalized = style.toLowerCase();
        if (normalized.startsWith("heading")) {
            String digits = normalized.replaceAll("\\D", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }
        if (normalized.startsWith("标题")) {
            String digits = normalized.replaceAll("\\D", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }
        return null;
    }

    private boolean isLikelyTitle(String text) {
        return text.length() <= 80 && !text.endsWith("。") && !text.endsWith(";") && !text.endsWith("；");
    }

    private void clearDeeperLevels(Map<Integer, Long> latestCatalogByLevel, int currentLevel) {
        latestCatalogByLevel.keySet().removeIf(level -> level > currentLevel);
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.replace('\u00A0', ' ')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", " ")
                .replaceAll("\\\\t", " ")
                .replaceAll("\\s+", " ")
                .trim();

        Matcher tocMatcher = TOC_FIELD_PATTERN.matcher(normalized);
        if (tocMatcher.find()) {
            return tocMatcher.group(1).trim();
        }
        return normalized;
    }

    private String preprocessHeadingText(String text) {
        Matcher pageNoMatcher = TRAILING_PAGE_NO_PATTERN.matcher(text);
        if (pageNoMatcher.matches()) {
            return pageNoMatcher.group(1).trim();
        }
        return text;
    }

    private static class ParseContext {
        private final String groupId;
        private final String docType;
        private final List<DocCatalog> catalogs = new ArrayList<>();
        private final List<DocContent> contents = new ArrayList<>();
        private final Map<Integer, Long> latestCatalogByLevel = new HashMap<>();
        private final Deque<String> titlePath = new ArrayDeque<>();
        private DocContent currentContent;

        private ParseContext(String groupId, String docType) {
            this.groupId = groupId;
            this.docType = docType;
        }

        private String getGroupId() {
            return groupId;
        }

        private String getDocType() {
            return docType;
        }

        private List<DocCatalog> getCatalogs() {
            return catalogs;
        }

        private List<DocContent> getContents() {
            return contents;
        }

        private Map<Integer, Long> getLatestCatalogByLevel() {
            return latestCatalogByLevel;
        }

        private Deque<String> getTitlePath() {
            return titlePath;
        }

        private DocContent getCurrentContent() {
            return currentContent;
        }

        private void setCurrentContent(DocContent currentContent) {
            this.currentContent = currentContent;
        }
    }
}
