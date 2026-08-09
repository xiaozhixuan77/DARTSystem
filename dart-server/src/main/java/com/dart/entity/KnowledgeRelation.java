package com.dart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识关系三元组：subject（知识条目）--predicate--> object（知识条目或外部概念）。
 * subject_id 外键级联删除；object 未匹配到知识库时以 object_label 记录外部概念。
 */
@Data
@TableName("knowledge_relation")
public class KnowledgeRelation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private String predicate;
    private Long objectId;
    private String objectLabel;
    private Double confidence;
    private String source;
    private LocalDateTime createdAt;
}
