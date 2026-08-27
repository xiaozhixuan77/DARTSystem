package com.dart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识实体：topic（白名单主题）/ concept（LLM 抽取的自由概念）。
 * (user_id, name) 唯一——同名实体跨条目自动聚合，条目经 knowledge_item_entity 关联。
 */
@Data
@TableName("knowledge_entity")
public class KnowledgeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String type;
    private String description;
    private String embedding;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
