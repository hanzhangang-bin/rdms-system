package com.rdms.controller;

import com.rdms.dto.ImportResponse;
import com.rdms.dto.TraceGraphDto;
import com.rdms.dto.TraceItemDto;
import com.rdms.service.TraceService;
import com.rdms.service.WordImportService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
                                         @RequestParam("docType") @NotBlank String docType) {
        return wordImportService.importWord(file, documentGroupId, docType);
    }

    @GetMapping("/trace-matrix")
    public List<TraceItemDto> traceMatrix(@RequestParam("documentGroupId") String documentGroupId) {
        return traceService.buildTraceMatrix(documentGroupId);
    }

    @GetMapping("/trace-graph")
    public TraceGraphDto traceGraph(@RequestParam("documentGroupId") String documentGroupId) {
        return traceService.buildTraceGraph(documentGroupId);
    }
}
