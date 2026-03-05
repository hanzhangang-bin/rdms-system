package com.rdms.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TraceGraphDto {

    private List<TraceGraphNodeDto> nodes;
    private List<TraceGraphEdgeDto> edges;
}
