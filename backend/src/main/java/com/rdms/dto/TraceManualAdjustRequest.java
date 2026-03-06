package com.rdms.dto;

import lombok.Data;

@Data
public class TraceManualAdjustRequest {

    private String documentGroupId;
    private String requirementCatalog;
    private String designCatalog;
    private String testCatalog;
}
