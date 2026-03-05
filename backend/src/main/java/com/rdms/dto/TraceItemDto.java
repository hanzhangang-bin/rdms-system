package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TraceItemDto {

    private String requirementCatalog;
    private String requirementTitle;
    private String designCatalog;
    private String designTitle;
    private String testCatalog;
    private String testTitle;
}
