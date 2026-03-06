package com.rdms.controller;

import com.rdms.dto.CatalogContentNodeDto;
import com.rdms.dto.CatalogContentUpdateRequest;
import com.rdms.dto.DocumentVersionDto;
import com.rdms.dto.ImportResponse;
import com.rdms.dto.TraceGraphDto;
import com.rdms.dto.TraceItemDto;
import com.rdms.dto.TraceManualAdjustRequest;
import com.rdms.service.TraceService;
import com.rdms.service.WordImportService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentController {

    private final WordImportService wordImportService;
    private final TraceService traceService;

    @PostMapping("/import")
    public ImportResponse importDocument(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "documentGroupId", required = false) String documentGroupId,
                                         @RequestParam("docType") @NotBlank String docType,
                                         @RequestParam(value = "versionNo", required = false) String versionNo) {
        return wordImportService.importWord(file, documentGroupId, docType, versionNo);
    }

    @GetMapping("/versions")
    public List<DocumentVersionDto> versions(@RequestParam("documentGroupId") String documentGroupId,
                                             @RequestParam("docType") String docType) {
        return wordImportService.listVersions(documentGroupId, docType);
    }

    @PostMapping("/catalog-content/update")
    public void updateCatalogContent(@RequestBody CatalogContentUpdateRequest request) {
        wordImportService.updateCatalogAndContent(request);
    }

    @GetMapping("/trace-matrix")
    public List<TraceItemDto> traceMatrix(@RequestParam("documentGroupId") String documentGroupId) {
        return traceService.buildTraceMatrix(documentGroupId);
    }

    @PostMapping("/trace-matrix/manual-adjust")
    public void manualAdjust(@RequestBody TraceManualAdjustRequest request) {
        traceService.saveManualAdjust(request);
    }

    @GetMapping("/trace-graph")
    public TraceGraphDto traceGraph(@RequestParam("documentGroupId") String documentGroupId) {
        return traceService.buildTraceGraph(documentGroupId);
    }

    @GetMapping("/catalog-content-tree")
    public List<CatalogContentNodeDto> catalogContentTree(@RequestParam("documentGroupId") String documentGroupId,
                                                          @RequestParam("docType") String docType,
                                                          @RequestParam(value = "versionNo", required = false) String versionNo) {
        return wordImportService.getCatalogContentTree(documentGroupId, docType, versionNo);
    }
}
