package com.rdms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.rdms.dto.CatalogContentNodeDto;
import com.rdms.dto.ImportResponse;
import com.rdms.entity.DocCatalog;
import com.rdms.entity.DocContent;
import com.rdms.entity.DocumentVersion;
import com.rdms.mapper.DocCatalogMapper;
import com.rdms.mapper.DocContentMapper;
import com.rdms.mapper.DocumentVersionMapper;
import com.rdms.service.WordImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WordImportServiceImpl implements WordImportService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("doc", "docx", "wps");
    private static final Set<String> SUPPORTED_DOC_TYPES = Set.of("REQUIREMENT", "DESIGN", "TESTCASE");

    private static final Pattern DECIMAL_TITLE_PATTERN = Pattern.compile("^((?:\\d+\\.)*\\d+)(?:[\\s、.．:：）)]*)\\s*(.+)$");
    private static final Pattern NUMERIC_TITLE_PATTERN = Pattern.compile("^(\\d+)(?:[、.．:：）)]|\\s)+(.+)$");
    private static final Pattern CN_SECTION_PATTERN = Pattern.compile("^第([一二三四五六七八九十百千万]+)([章节部分])\\s+(.+)$");
    private static final Pattern BULLET_LEVEL_PATTERN = Pattern.compile("^([（(]?[一二三四五六七八九十]+[）)])\\s*(.+)$");
    private static final Pattern CN_LEVEL_PATTERN = Pattern.compile("^([一二三四五六七八九十]+)[、.．]\\s*(.+)$");
    private static final Pattern TOC_FIELD_PATTERN = Pattern.compile(
            "HYPERLINK\\s+\\\\l\\s+_Toc\\d+\\s+(.+?)\\s+PAGEREF\\s+_Toc\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_PAGE_NO_PATTERN = Pattern.compile("^(.+?)\\s+(\\d{1,4})$");

    private final DocCatalogMapper docCatalogMapper;
    private final DocContentMapper docContentMapper;
    private final DocumentVersionMapper documentVersionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResponse importWord(MultipartFile file, String documentGroupId, String docType, String versionNo) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String normalizedDocType = normalizeDocType(docType);
        String extension = validateAndGetExtension(file.getOriginalFilename());
        String groupId = StringUtils.hasText(documentGroupId) ? documentGroupId : UUID.randomUUID().toString();
        String normalizedVersionNo = StringUtils.hasText(versionNo) ? versionNo.trim() : generateVersionNo();

        markLatestVersion(groupId, normalizedDocType, normalizedVersionNo);
        ParseContext context = new ParseContext(groupId, normalizedDocType, normalizedVersionNo);

        try {
            List<RawParagraph> paragraphs = readParagraphs(file.getBytes(), extension);
            Map<String, HeadingInfo> tocHints = extractTocHints(paragraphs);
            for (RawParagraph paragraph : paragraphs) {
                processParagraph(context, paragraph, tocHints);
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
                .versionNo(normalizedVersionNo)
                .build();
    }

    @Override
    public List<CatalogContentNodeDto> getCatalogContentTree(String documentGroupId, String docType, String versionNo) {
        String normalizedDocType = normalizeDocType(docType);

        String normalizedVersionNo = resolveVersionNo(documentGroupId, normalizedDocType, versionNo);

        List<DocCatalog> catalogs = docCatalogMapper.selectList(new LambdaQueryWrapper<DocCatalog>()
                .eq(DocCatalog::getDocumentGroupId, documentGroupId)
                .eq(DocCatalog::getDocType, normalizedDocType)
                .eq(DocCatalog::getVersionNo, normalizedVersionNo)
                .orderByAsc(DocCatalog::getCatalogLevel)
                .orderByAsc(DocCatalog::getId));

        if (catalogs.isEmpty()) {
            return List.of();
        }

        List<DocContent> contents = docContentMapper.selectList(new LambdaQueryWrapper<DocContent>()
                .eq(DocContent::getDocumentGroupId, documentGroupId)
                .eq(DocContent::getVersionNo, normalizedVersionNo));

        Map<Long, String> contentByCatalogId = new HashMap<>();
        for (DocContent content : contents) {
            contentByCatalogId.put(content.getCatalogId(), content.getContentText());
        }

        Map<Long, CatalogContentNodeDto> nodeById = new HashMap<>();
        List<CatalogContentNodeDto> roots = new ArrayList<>();

        for (DocCatalog catalog : catalogs) {
            CatalogContentNodeDto node = CatalogContentNodeDto.builder()
                    .catalogId(catalog.getId())
                    .parentId(catalog.getParentId())
                    .catalogNo(catalog.getCatalogNo())
                    .title(catalog.getTitle())
                    .catalogLevel(catalog.getCatalogLevel())
                    .fullPath(catalog.getFullPath())
                    .contentText(contentByCatalogId.get(catalog.getId()))
                    .build();
            nodeById.put(catalog.getId(), node);
        }

        for (DocCatalog catalog : catalogs) {
            CatalogContentNodeDto node = nodeById.get(catalog.getId());
            if (catalog.getParentId() == null || !nodeById.containsKey(catalog.getParentId())) {
                roots.add(node);
            } else {
                nodeById.get(catalog.getParentId()).getChildren().add(node);
            }
        }
        return roots;
    }

    private void processParagraph(ParseContext context, RawParagraph paragraph, Map<String, HeadingInfo> tocHints) {
        if (isTocFieldLine(paragraph.getText())) {
            return;
        }

        String text = cleanText(paragraph.getText());
        if (text.isEmpty()) {
            return;
        }

        HeadingInfo heading = parseHeading(paragraph, text, tocHints);
        if (heading != null) {
            while (context.getTitlePath().size() >= heading.getLevel()) {
                context.getTitlePath().pollLast();
            }
            context.getTitlePath().addLast(heading.getTitle());

            DocCatalog catalog = new DocCatalog();
            catalog.setDocumentGroupId(context.getGroupId());
            catalog.setVersionNo(context.getVersionNo());
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
            content.setVersionNo(context.getVersionNo());
            content.setContentText("");
            context.getContents().add(content);
            context.setCurrentContent(content);
            return;
        }

        if (context.getCurrentContent() != null) {
            appendHtml(context.getCurrentContent(), paragraph, text);
        }
    }

    private void appendHtml(DocContent currentContent, RawParagraph paragraph, String fallbackText) {
        String old = currentContent.getContentText();
        String html = paragraph.getHtml();
        if (!StringUtils.hasText(html)) {
            html = "<p>" + escapeHtml(fallbackText) + "</p>";
        }
        currentContent.setContentText((old == null || old.isEmpty()) ? html : old + html);
    }

    private Map<String, HeadingInfo> extractTocHints(List<RawParagraph> paragraphs) {
        Map<String, HeadingInfo> hints = new LinkedHashMap<>();
        for (RawParagraph paragraph : paragraphs) {
            String raw = paragraph.getText();
            if (!isTocFieldLine(raw)) {
                continue;
            }
            String normalized = cleanText(raw);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            String headingText = preprocessHeadingText(normalized);
            HeadingInfo parsed = parseByDecimal(headingText);
            if (parsed == null) {
                parsed = parseByNumeric(headingText);
            }
            if (parsed == null) {
                parsed = parseByChineseSection(headingText);
            }
            if (parsed == null && isLikelyTitle(headingText)) {
                parsed = new HeadingInfo(String.valueOf(hints.size() + 1), headingText, 1);
            }
            if (parsed != null) {
                hints.put(normalizeKey(parsed.getTitle()), parsed);
            }
        }
        return hints;
    }

    private boolean isTocFieldLine(String rawText) {
        return rawText != null && rawText.toUpperCase().contains("HYPERLINK") && rawText.contains("_Toc");
    }

    private String normalizeKey(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
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
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph paragraph = (XWPFParagraph) bodyElement;
                    int levelHint = parseLevelHint(paragraph);
                    result.add(new RawParagraph(resolveParagraphStyle(paragraph, document), paragraph.getText(), levelHint, paragraphToHtml(paragraph)));
                } else if (bodyElement.getElementType() == BodyElementType.TABLE) {
                    XWPFTable table = (XWPFTable) bodyElement;
                    result.add(new RawParagraph(null, table.getText(), 0, tableToHtml(table)));
                }
            }
        }
        return result;
    }

    private String resolveParagraphStyle(XWPFParagraph paragraph, XWPFDocument document) {
        String styleId = paragraph.getStyle();
        if (!StringUtils.hasText(styleId) || document.getStyles() == null) {
            return styleId;
        }
        XWPFStyle style = document.getStyles().getStyle(styleId);
        if (style == null || !StringUtils.hasText(style.getName())) {
            return styleId;
        }
        return styleId + "|" + style.getName();
    }

    private String paragraphToHtml(XWPFParagraph paragraph) {
        StringBuilder inner = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            inner.append(runToHtml(run));
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                XWPFPictureData pictureData = picture.getPictureData();
                if (pictureData != null && pictureData.getData() != null) {
                    String mime = pictureData.getPackagePart() != null
                            ? pictureData.getPackagePart().getContentType() : "image/png";
                    String base64 = Base64.getEncoder().encodeToString(pictureData.getData());
                    inner.append("<img src='")
                            .append("data:").append(mime).append(";base64,").append(base64)
                            .append("' alt='image' />");
                }
            }
        }

        if (inner.length() == 0) {
            return "";
        }
        return "<p>" + inner + "</p>";
    }

    private String runToHtml(XWPFRun run) {
        String text = run.text();
        if (!StringUtils.hasText(text)) {
            return "";
        }

        StringBuilder style = new StringBuilder();
        if (StringUtils.hasText(run.getColor())) {
            style.append("color:#").append(run.getColor()).append(";");
        }
        if (run.getFontSize() > 0) {
            style.append("font-size:").append(run.getFontSize()).append("pt;");
        }
        if (StringUtils.hasText(run.getFontFamily())) {
            style.append("font-family:").append(run.getFontFamily()).append(";");
        }

        String html = escapeHtml(text).replace("\n", "<br/>");
        if (style.length() > 0) {
            html = "<span style=\"" + style + "\">" + html + "</span>";
        }
        if (run.isBold()) {
            html = "<strong>" + html + "</strong>";
        }
        if (run.isItalic()) {
            html = "<em>" + html + "</em>";
        }
        if (run.getUnderline() != null && run.getUnderline().getValue() != 0) {
            html = "<u>" + html + "</u>";
        }
        if (run.isStrikeThrough()) {
            html = "<del>" + html + "</del>";
        }
        return html;
    }

    private String tableToHtml(XWPFTable table) {
        StringBuilder html = new StringBuilder("<table border='1' style='border-collapse:collapse;'>");
        for (XWPFTableRow row : table.getRows()) {
            html.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder cellHtml = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    String ph = paragraphToHtml(p);
                    if (StringUtils.hasText(ph)) {
                        cellHtml.append(ph);
                    }
                }
                if (cellHtml.length() == 0) {
                    cellHtml.append(escapeHtml(cell.getText()));
                }
                html.append("<td>").append(cellHtml).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
        return html.toString();
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
                String clean = cleanText(p);
                String html = clean.isEmpty() ? "" : "<p>" + escapeHtml(clean) + "</p>";
                result.add(new RawParagraph(null, p, 0, html));
            }
            return result;
        }
    }

    private HeadingInfo parseHeading(RawParagraph paragraph, String text, Map<String, HeadingInfo> tocHints) {
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
        if (bulletMatcher.matches() && isLikelyStructuredHeading(bulletMatcher.group(2))) {
            return new HeadingInfo(bulletMatcher.group(1), bulletMatcher.group(2), Math.max(paragraph.getLevelHint(), 3));
        }

        HeadingInfo byCnLevel = parseByChineseItem(headingText);
        if (byCnLevel != null) {
            return byCnLevel;
        }

        HeadingInfo byToc = tocHints.get(normalizeKey(headingText));
        if (byToc != null) {
            return new HeadingInfo(byToc.getCatalogNo(), headingText, byToc.getLevel());
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
        if (!isLikelyStructuredHeading(title)) {
            return null;
        }
        int level = number.split("\\.").length;
        return new HeadingInfo(number, title, level);
    }

    private HeadingInfo parseByNumeric(String text) {
        Matcher matcher = NUMERIC_TITLE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String title = matcher.group(2);
        if (!isLikelyStructuredHeading(title)) {
            return null;
        }
        return new HeadingInfo(matcher.group(1), title, 1);
    }

    private HeadingInfo parseByChineseSection(String text) {
        Matcher matcher = CN_SECTION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String no = "第" + matcher.group(1) + matcher.group(2);
        String title = matcher.group(3);
        if (!isLikelyStructuredHeading(title)) {
            return null;
        }
        int level = "章".equals(matcher.group(2)) ? 1 : 2;
        return new HeadingInfo(no, title, level);
    }

    private HeadingInfo parseByChineseItem(String text) {
        Matcher matcher = CN_LEVEL_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String title = matcher.group(2);
        if (!isLikelyStructuredHeading(title)) {
            return null;
        }
        return new HeadingInfo(matcher.group(1), title, 2);
    }

    private Integer parseHeadingLevelByStyle(String style) {
        if (!StringUtils.hasText(style)) {
            return null;
        }

        for (String candidate : style.split("\\|")) {
            String normalized = candidate.toLowerCase().trim();
            if (normalized.startsWith("heading") || normalized.startsWith("标题")) {
                String digits = normalized.replaceAll("\\D", "");
                return digits.isEmpty() ? 1 : Integer.parseInt(digits);
            }
        }
        return null;
    }

    private boolean isLikelyStructuredHeading(String title) {
        if (!isLikelyTitle(title)) {
            return false;
        }
        if (title.length() > 40) {
            return false;
        }
        return !title.matches(".*[，,。；;！？?!].*");
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
                .replace("\t", " ")
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

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }


    @Override
    public List<com.rdms.dto.DocumentVersionDto> listVersions(String documentGroupId, String docType) {
        String normalizedDocType = normalizeDocType(docType);
        return documentVersionMapper.selectList(new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocumentGroupId, documentGroupId)
                        .eq(DocumentVersion::getDocType, normalizedDocType)
                        .orderByDesc(DocumentVersion::getCreatedAt))
                .stream()
                .map(v -> com.rdms.dto.DocumentVersionDto.builder()
                        .id(v.getId())
                        .documentGroupId(v.getDocumentGroupId())
                        .docType(v.getDocType())
                        .versionNo(v.getVersionNo())
                        .isLatest(v.getIsLatest())
                        .createdAt(v.getCreatedAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCatalogAndContent(com.rdms.dto.CatalogContentUpdateRequest request) {
        if (request == null || request.getCatalogId() == null) {
            throw new IllegalArgumentException("catalogId不能为空");
        }
        DocCatalog catalog = docCatalogMapper.selectById(request.getCatalogId());
        if (catalog == null) {
            throw new IllegalArgumentException("目录不存在");
        }
        if (StringUtils.hasText(request.getTitle())) {
            catalog.setTitle(request.getTitle().trim());
            docCatalogMapper.updateById(catalog);
        }

        DocContent content = docContentMapper.selectOne(new LambdaQueryWrapper<DocContent>()
                .eq(DocContent::getCatalogId, request.getCatalogId())
                .last("limit 1"));
        if (content == null) {
            content = new DocContent();
            content.setCatalogId(request.getCatalogId());
            content.setDocumentGroupId(catalog.getDocumentGroupId());
            content.setVersionNo(catalog.getVersionNo());
        }
        if (request.getContentHtml() != null) {
            content.setContentText(request.getContentHtml());
        }
        if (content.getId() == null) {
            docContentMapper.insert(content);
        } else {
            docContentMapper.updateById(content);
        }
    }

    private void markLatestVersion(String groupId, String docType, String versionNo) {
        List<DocumentVersion> exists = documentVersionMapper.selectList(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentGroupId, groupId)
                .eq(DocumentVersion::getDocType, docType));
        for (DocumentVersion item : exists) {
            item.setIsLatest(0);
            documentVersionMapper.updateById(item);
        }

        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentGroupId, groupId)
                .eq(DocumentVersion::getDocType, docType)
                .eq(DocumentVersion::getVersionNo, versionNo)
                .last("limit 1"));
        if (version == null) {
            version = new DocumentVersion();
            version.setDocumentGroupId(groupId);
            version.setDocType(docType);
            version.setVersionNo(versionNo);
            version.setCreatedAt(LocalDateTime.now());
            version.setIsLatest(1);
            documentVersionMapper.insert(version);
        } else {
            version.setIsLatest(1);
            documentVersionMapper.updateById(version);
        }
    }

    private String resolveVersionNo(String groupId, String docType, String versionNo) {
        if (StringUtils.hasText(versionNo)) {
            return versionNo.trim();
        }
        DocumentVersion latest = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentGroupId, groupId)
                .eq(DocumentVersion::getDocType, docType)
                .eq(DocumentVersion::getIsLatest, 1)
                .orderByDesc(DocumentVersion::getCreatedAt)
                .last("limit 1"));
        return latest == null ? "v1" : latest.getVersionNo();
    }

    private String generateVersionNo() {
        return "v" + System.currentTimeMillis();
    }

    private static class ParseContext {
        private final String groupId;
        private final String docType;
        private final String versionNo;
        private final List<DocCatalog> catalogs = new ArrayList<>();
        private final List<DocContent> contents = new ArrayList<>();
        private final Map<Integer, Long> latestCatalogByLevel = new HashMap<>();
        private final Deque<String> titlePath = new ArrayDeque<>();
        private DocContent currentContent;

        private ParseContext(String groupId, String docType, String versionNo) {
            this.groupId = groupId;
            this.docType = docType;
            this.versionNo = versionNo;
        }

        private String getGroupId() {
            return groupId;
        }

        private String getDocType() {
            return docType;
        }

        private String getVersionNo() {
            return versionNo;
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
