package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TraceGraphNodeDto {

    private String id;
    private String type;
    private String catalogNo;
    private String title;
}
