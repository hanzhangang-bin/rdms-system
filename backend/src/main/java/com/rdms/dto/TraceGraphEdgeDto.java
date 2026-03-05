package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TraceGraphEdgeDto {

    private String source;
    private String target;
    private String relation;
    private Integer score;
}
