package com.rdms.service;

import com.rdms.dto.ImportResponse;
import org.springframework.web.multipart.MultipartFile;

public interface WordImportService {

    ImportResponse importWord(MultipartFile file, String documentGroupId, String docType);
}
