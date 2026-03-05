package com.rdms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("doc_content")
public class DocContent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long catalogId;

    private String documentGroupId;

    private String contentText;
}
