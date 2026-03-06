package com.rdms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("doc_catalog")
public class DocCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentGroupId;

    private String versionNo;

    private String docType;

    private String catalogNo;

    private String title;

    private Integer catalogLevel;

    private Long parentId;

    private String fullPath;
}
