package com.dart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识条目-实体关联（多对多）。item_id 外键级联删除，删除条目自动清关联。
 */
@Data
@TableName("knowledge_item_entity")
public class KnowledgeItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Long entityId;
    private LocalDateTime createdAt;
}
