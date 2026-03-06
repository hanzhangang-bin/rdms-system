package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentVersionDto {

    private Long id;
    private String documentGroupId;
    private String docType;
    private String versionNo;
    private Integer isLatest;
    private LocalDateTime createdAt;
}
