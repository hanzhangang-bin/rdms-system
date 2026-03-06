package com.rdms.service;

import com.rdms.dto.CatalogContentNodeDto;
import com.rdms.dto.CatalogContentUpdateRequest;
import com.rdms.dto.DocumentVersionDto;
import com.rdms.dto.ImportResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface WordImportService {

    ImportResponse importWord(MultipartFile file, String documentGroupId, String docType, String versionNo);

    List<CatalogContentNodeDto> getCatalogContentTree(String documentGroupId, String docType, String versionNo);

    List<DocumentVersionDto> listVersions(String documentGroupId, String docType);

    void updateCatalogAndContent(CatalogContentUpdateRequest request);
}
