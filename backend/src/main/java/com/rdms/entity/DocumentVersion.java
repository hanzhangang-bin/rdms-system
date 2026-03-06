package com.rdms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doc_version")
public class DocumentVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentGroupId;

    private String docType;

    private String versionNo;

    private Integer isLatest;

    private LocalDateTime createdAt;
}
