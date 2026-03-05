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
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WordImportServiceImpl implements WordImportService {

    private static final Pattern DECIMAL_TITLE_PATTERN = Pattern.compile("^((?:\\d+\\.)*\\d+)\\s+(.+)$");
    private static final Pattern NUMERIC_TITLE_PATTERN = Pattern.compile("^(\\d+)[、.．]\\s*(.+)$");
    private static final Pattern CN_SECTION_PATTERN = Pattern.compile("^第([一二三四五六七八九十百千万]+)([章节部分])\\s+(.+)$");
    private static final Pattern BULLET_LEVEL_PATTERN = Pattern.compile("^([（(]?[一二三四五六七八九十]+[）)])\\s*(.+)$");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResponse importWord(MultipartFile file, String documentGroupId, String docType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        validateExtension(file.getOriginalFilename());

        String groupId = StringUtils.hasText(documentGroupId) ? documentGroupId : UUID.randomUUID().toString();

        List<DocCatalog> catalogs = new ArrayList<>();
        Map<Integer, Long> latestCatalogByLevel = new HashMap<>();
        Map<Long, StringBuilder> contentMap = new HashMap<>();
        Deque<String> titlePath = new ArrayDeque<>();
        Long currentCatalogId = null;

        try {
            List<RawParagraph> paragraphs = readParagraphs(file);
            for (RawParagraph paragraph : paragraphs) {
                String text = cleanText(paragraph.text());
                if (text.isEmpty()) {
                    continue;
                }

                HeadingInfo heading = parseHeading(paragraph, text);
                if (heading != null) {
                    while (titlePath.size() >= heading.level()) {
                        titlePath.pollLast();
                    }
                    DocCatalog catalog = buildCatalog(groupId, docType, heading, latestCatalogByLevel, titlePath);
                    Db.save(catalog);
                    catalogs.add(catalog);
                    latestCatalogByLevel.put(heading.level(), catalog.getId());
                    clearDeeperLevels(latestCatalogByLevel, heading.level());

                    currentCatalogId = catalog.getId();
                    contentMap.putIfAbsent(currentCatalogId, new StringBuilder());
                    continue;
                }

                if (currentCatalogId != null) {
                    contentMap.computeIfAbsent(currentCatalogId, key -> new StringBuilder())
                            .append(text)
                            .append("\n");
                }
            }
        } catch (IOException e) {
            log.error("导入Word失败", e);
            throw new IllegalStateException("解析Word文档失败", e);
        }

        contentMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    DocContent content = new DocContent();
                    content.setCatalogId(entry.getKey());
                    content.setDocumentGroupId(groupId);
                    content.setContentText(entry.getValue().toString().trim());
                    Db.save(content);
                });

        return ImportResponse.builder()
                .documentGroupId(groupId)
                .docType(docType.toUpperCase())
                .catalogCount(catalogs.size())
                .build();
    }

    private void validateExtension(String filename) {
        String ext = getExtension(filename);
        if (!("docx".equals(ext) || "doc".equals(ext) || "wps".equals(ext))) {
            throw new IllegalArgumentException("仅支持 doc/docx/wps 格式");
        }
    }

    private List<RawParagraph> readParagraphs(MultipartFile file) throws IOException {
        String ext = getExtension(file.getOriginalFilename());
        byte[] bytes = file.getBytes();

        if ("docx".equals(ext)) {
            return readDocxParagraphs(bytes);
        }
        if ("doc".equals(ext)) {
            return readDocParagraphs(bytes);
        }
        if ("wps".equals(ext)) {
            try {
                return readDocParagraphs(bytes);
            } catch (Exception ignore) {
                return readDocxParagraphs(bytes);
            }
        }
        throw new IllegalArgumentException("仅支持 doc/docx/wps 格式");
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

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private DocCatalog buildCatalog(String groupId,
                                    String docType,
                                    HeadingInfo heading,
                                    Map<Integer, Long> latestCatalogByLevel,
                                    Deque<String> titlePath) {
        DocCatalog catalog = new DocCatalog();
        titlePath.addLast(heading.title());
        catalog.setDocumentGroupId(groupId);
        catalog.setDocType(docType.toUpperCase());
        catalog.setCatalogNo(heading.catalogNo());
        catalog.setTitle(heading.title());
        catalog.setCatalogLevel(heading.level());
        catalog.setParentId(heading.level() > 1 ? latestCatalogByLevel.get(heading.level() - 1) : null);
        catalog.setFullPath(String.join(" / ", titlePath));
        return catalog;
    }

    private HeadingInfo parseHeading(RawParagraph paragraph, String text) {
        Integer styleLevel = parseHeadingLevelByStyle(paragraph.style());
        if (styleLevel != null) {
            HeadingInfo byNo = parseByNumbering(text, styleLevel);
            return byNo != null ? byNo : new HeadingInfo(String.valueOf(styleLevel), text, styleLevel);
        }

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

        Matcher bulletMatcher = BULLET_LEVEL_PATTERN.matcher(text);
        if (bulletMatcher.matches()) {
            return new HeadingInfo(bulletMatcher.group(1), bulletMatcher.group(2), Math.max(paragraph.levelHint(), 3));
        }

        if (paragraph.levelHint() > 0 && isLikelyTitle(text)) {
            return new HeadingInfo(String.valueOf(paragraph.levelHint()), text, paragraph.levelHint());
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
        return text == null ? "" : text.replace('\u00A0', ' ').trim();
    }

    private static class HeadingInfo {
        private final String catalogNo;
        private final String title;
        private final int level;

        private HeadingInfo(String catalogNo, String title, int level) {
            this.catalogNo = catalogNo;
            this.title = title;
            this.level = level;
        }

        private String catalogNo() {
            return catalogNo;
        }

        private String title() {
            return title;
        }

        private int level() {
            return level;
        }
    }

    private static class RawParagraph {
        private final String style;
        private final String text;
        private final int levelHint;

        private RawParagraph(String style, String text, int levelHint) {
            this.style = style;
            this.text = text;
            this.levelHint = levelHint;
        }

        private String style() {
            return style;
        }

        private String text() {
            return text;
        }

        private int levelHint() {
            return levelHint;
        }
    }
}
