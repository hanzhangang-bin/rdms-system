package com.rdms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("trace_matrix_manual")
public class TraceMatrixManual {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentGroupId;

    private String requirementCatalog;

    private String designCatalog;

    private String testCatalog;
}
