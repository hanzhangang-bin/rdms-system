package com.rdms.dto;

import lombok.Data;

@Data
public class CatalogContentUpdateRequest {

    private Long catalogId;
    private String title;
    private String contentHtml;
}
