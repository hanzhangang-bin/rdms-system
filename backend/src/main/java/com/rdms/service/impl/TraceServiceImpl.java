package com.rdms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rdms.dto.TraceGraphDto;
import com.rdms.dto.TraceGraphEdgeDto;
import com.rdms.dto.TraceGraphNodeDto;
import com.rdms.dto.TraceItemDto;
import com.rdms.dto.TraceManualAdjustRequest;
import com.rdms.entity.DocCatalog;
import com.rdms.entity.DocumentVersion;
import com.rdms.entity.TraceMatrixManual;
import com.rdms.mapper.DocCatalogMapper;
import com.rdms.mapper.DocumentVersionMapper;
import com.rdms.mapper.TraceMatrixManualMapper;
import com.rdms.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TraceServiceImpl implements TraceService {

    private static final String TYPE_REQUIREMENT = "REQUIREMENT";
    private static final String TYPE_DESIGN = "DESIGN";
    private static final String TYPE_TESTCASE = "TESTCASE";

    private final DocCatalogMapper docCatalogMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final TraceMatrixManualMapper traceMatrixManualMapper;

    @Override
    public List<TraceItemDto> buildTraceMatrix(String documentGroupId) {
        CatalogContext context = loadContext(documentGroupId);
        Map<String, TraceMatrixManual> manualMap = loadManualMap(documentGroupId);

        List<TraceItemDto> result = new ArrayList<>();
        for (DocCatalog req : context.requirements()) {
            MatchResult design = findMatched(req, context.designs().values());
            MatchResult testcase = findMatched(req, context.tests().values());

            TraceItemDto row = TraceItemDto.builder()
                    .requirementCatalog(req.getCatalogNo())
                    .requirementTitle(req.getTitle())
                    .designCatalog(design.catalog() == null ? null : design.catalog().getCatalogNo())
                    .designTitle(design.catalog() == null ? null : design.catalog().getTitle())
                    .testCatalog(testcase.catalog() == null ? null : testcase.catalog().getCatalogNo())
                    .testTitle(testcase.catalog() == null ? null : testcase.catalog().getTitle())
                    .build();

            applyManualAdjust(row, context, manualMap.get(req.getCatalogNo()));
            result.add(row);
        }

        return result.stream()
                .sorted(Comparator.comparing(TraceItemDto::getRequirementCatalog, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public TraceGraphDto buildTraceGraph(String documentGroupId) {
        CatalogContext context = loadContext(documentGroupId);
        Map<String, TraceGraphNodeDto> nodes = new LinkedHashMap<>();
        List<TraceGraphEdgeDto> edges = new ArrayList<>();

        context.requirements().forEach(req -> nodes.put(nodeId(TYPE_REQUIREMENT, req.getId()), toNode(TYPE_REQUIREMENT, req)));
        context.designs().values().forEach(design -> nodes.put(nodeId(TYPE_DESIGN, design.getId()), toNode(TYPE_DESIGN, design)));
        context.tests().values().forEach(test -> nodes.put(nodeId(TYPE_TESTCASE, test.getId()), toNode(TYPE_TESTCASE, test)));

        for (DocCatalog req : context.requirements()) {
            MatchResult design = findMatched(req, context.designs().values());
            if (design.catalog() != null) {
                edges.add(TraceGraphEdgeDto.builder()
                        .source(nodeId(TYPE_REQUIREMENT, req.getId()))
                        .target(nodeId(TYPE_DESIGN, design.catalog().getId()))
                        .relation("REQ_TO_DESIGN")
                        .score(design.score())
                        .build());
            }

            DocCatalog testAnchor = design.catalog() != null ? design.catalog() : req;
            MatchResult testcase = findMatched(testAnchor, context.tests().values());
            if (testcase.catalog() != null) {
                edges.add(TraceGraphEdgeDto.builder()
                        .source(design.catalog() == null ? nodeId(TYPE_REQUIREMENT, req.getId()) : nodeId(TYPE_DESIGN, design.catalog().getId()))
                        .target(nodeId(TYPE_TESTCASE, testcase.catalog().getId()))
                        .relation("DESIGN_TO_TEST")
                        .score(testcase.score())
                        .build());
            }
        }

        return TraceGraphDto.builder()
                .nodes(new ArrayList<>(nodes.values()))
                .edges(edges)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveManualAdjust(TraceManualAdjustRequest request) {
        if (request == null || !StringUtils.hasText(request.getDocumentGroupId())
                || !StringUtils.hasText(request.getRequirementCatalog())) {
            throw new IllegalArgumentException("documentGroupId和requirementCatalog不能为空");
        }

        TraceMatrixManual manual = traceMatrixManualMapper.selectOne(new LambdaQueryWrapper<TraceMatrixManual>()
                .eq(TraceMatrixManual::getDocumentGroupId, request.getDocumentGroupId())
                .eq(TraceMatrixManual::getRequirementCatalog, request.getRequirementCatalog().trim())
                .last("limit 1"));

        if (manual == null) {
            manual = new TraceMatrixManual();
            manual.setDocumentGroupId(request.getDocumentGroupId().trim());
            manual.setRequirementCatalog(request.getRequirementCatalog().trim());
        }

        manual.setDesignCatalog(trimToNull(request.getDesignCatalog()));
        manual.setTestCatalog(trimToNull(request.getTestCatalog()));

        if (manual.getId() == null) {
            traceMatrixManualMapper.insert(manual);
        } else {
            traceMatrixManualMapper.updateById(manual);
        }
    }

    private CatalogContext loadContext(String documentGroupId) {
        String reqVersion = resolveLatestVersion(documentGroupId, TYPE_REQUIREMENT);
        String designVersion = resolveLatestVersion(documentGroupId, TYPE_DESIGN);
        String testVersion = resolveLatestVersion(documentGroupId, TYPE_TESTCASE);

        List<DocCatalog> allCatalog = new ArrayList<>();
        allCatalog.addAll(loadCatalogByTypeAndVersion(documentGroupId, TYPE_REQUIREMENT, reqVersion));
        allCatalog.addAll(loadCatalogByTypeAndVersion(documentGroupId, TYPE_DESIGN, designVersion));
        allCatalog.addAll(loadCatalogByTypeAndVersion(documentGroupId, TYPE_TESTCASE, testVersion));

        Map<String, List<DocCatalog>> catalogByType = allCatalog.stream()
                .collect(Collectors.groupingBy(DocCatalog::getDocType));

        List<DocCatalog> requirements = catalogByType.getOrDefault(TYPE_REQUIREMENT, List.of());
        Map<String, DocCatalog> designsByNo = catalogByType.getOrDefault(TYPE_DESIGN, List.of())
                .stream().collect(Collectors.toMap(DocCatalog::getCatalogNo, Function.identity(), (a, b) -> a));
        Map<String, DocCatalog> testsByNo = catalogByType.getOrDefault(TYPE_TESTCASE, List.of())
                .stream().collect(Collectors.toMap(DocCatalog::getCatalogNo, Function.identity(), (a, b) -> a));
        return new CatalogContext(requirements, designsByNo, testsByNo);
    }

    private List<DocCatalog> loadCatalogByTypeAndVersion(String documentGroupId, String docType, String versionNo) {
        LambdaQueryWrapper<DocCatalog> query = new LambdaQueryWrapper<DocCatalog>()
                .eq(DocCatalog::getDocumentGroupId, documentGroupId)
                .eq(DocCatalog::getDocType, docType);
        if (StringUtils.hasText(versionNo)) {
            query.eq(DocCatalog::getVersionNo, versionNo);
        }
        return docCatalogMapper.selectList(query);
    }

    private String resolveLatestVersion(String documentGroupId, String docType) {
        DocumentVersion latest = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getDocumentGroupId, documentGroupId)
                .eq(DocumentVersion::getDocType, docType)
                .eq(DocumentVersion::getIsLatest, 1)
                .orderByDesc(DocumentVersion::getCreatedAt)
                .last("limit 1"));
        return latest == null ? null : latest.getVersionNo();
    }

    private Map<String, TraceMatrixManual> loadManualMap(String documentGroupId) {
        return traceMatrixManualMapper.selectList(new LambdaQueryWrapper<TraceMatrixManual>()
                        .eq(TraceMatrixManual::getDocumentGroupId, documentGroupId))
                .stream()
                .collect(Collectors.toMap(TraceMatrixManual::getRequirementCatalog, Function.identity(), (a, b) -> b));
    }

    private void applyManualAdjust(TraceItemDto row, CatalogContext context, TraceMatrixManual manual) {
        if (manual == null) {
            return;
        }

        row.setDesignCatalog(manual.getDesignCatalog());
        DocCatalog design = context.designs().get(manual.getDesignCatalog());
        row.setDesignTitle(design == null ? null : design.getTitle());

        row.setTestCatalog(manual.getTestCatalog());
        DocCatalog test = context.tests().get(manual.getTestCatalog());
        row.setTestTitle(test == null ? null : test.getTitle());
    }

    private TraceGraphNodeDto toNode(String type, DocCatalog catalog) {
        return TraceGraphNodeDto.builder()
                .id(nodeId(type, catalog.getId()))
                .type(type)
                .catalogNo(catalog.getCatalogNo())
                .title(catalog.getTitle())
                .build();
    }

    private String nodeId(String type, Long id) {
        return type + "-" + id;
    }

    private MatchResult findMatched(DocCatalog base, Collection<DocCatalog> candidates) {
        Optional<DocCatalog> exact = candidates.stream()
                .filter(item -> Objects.equals(item.getCatalogNo(), base.getCatalogNo()))
                .findFirst();
        if (exact.isPresent()) {
            return new MatchResult(exact.get(), 100);
        }

        Optional<DocCatalog> prefix = candidates.stream()
                .filter(item -> item.getCatalogNo() != null && base.getCatalogNo() != null &&
                        (item.getCatalogNo().startsWith(base.getCatalogNo()) || base.getCatalogNo().startsWith(item.getCatalogNo())))
                .findFirst();
        if (prefix.isPresent()) {
            return new MatchResult(prefix.get(), 85);
        }

        return candidates.stream()
                .map(item -> new MatchResult(item, titleSimilarity(base.getTitle(), item.getTitle())))
                .filter(item -> item.score() >= 50)
                .max(Comparator.comparing(MatchResult::score))
                .orElse(new MatchResult(null, 0));
    }

    private int titleSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0;
        }
        String a = normalizeText(left);
        String b = normalizeText(right);
        if (a.equals(b)) {
            return 95;
        }
        int lcs = longestCommonSubstring(a, b);
        int score = (int) ((lcs * 200.0) / (a.length() + b.length()));
        return Math.min(score, 90);
    }

    private String normalizeText(String text) {
        return text.replaceAll("\\s+", "").toLowerCase();
    }

    private int longestCommonSubstring(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        int max = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    max = Math.max(max, dp[i][j]);
                }
            }
        }
        return max;
    }

    private String trimToNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return text.trim();
    }

    private record CatalogContext(List<DocCatalog> requirements, Map<String, DocCatalog> designs, Map<String, DocCatalog> tests) {
    }

    private record MatchResult(DocCatalog catalog, int score) {
    }
}
