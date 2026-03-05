package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class CatalogContentNodeDto {

    private Long catalogId;
    private Long parentId;
    private String catalogNo;
    private String title;
    private Integer catalogLevel;
    private String fullPath;
    private String contentText;

    @Builder.Default
    private List<CatalogContentNodeDto> children = new ArrayList<>();
}
