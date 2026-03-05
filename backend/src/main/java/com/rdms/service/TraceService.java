package com.rdms.service;

import com.rdms.dto.TraceGraphDto;
import com.rdms.dto.TraceItemDto;

import java.util.List;

public interface TraceService {

    List<TraceItemDto> buildTraceMatrix(String documentGroupId);

    TraceGraphDto buildTraceGraph(String documentGroupId);
}
