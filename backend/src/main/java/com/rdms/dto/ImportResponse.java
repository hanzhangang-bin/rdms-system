package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportResponse {

    private String documentGroupId;

    private String docType;

    private Integer catalogCount;

    private String versionNo;
}
