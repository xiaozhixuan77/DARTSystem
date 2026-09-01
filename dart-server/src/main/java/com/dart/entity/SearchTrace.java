package com.dart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 搜索链路追踪：单次查询的完整决策链（意图 → RAG/搜索 → 整理 → 降级），
 * 支撑质量分析与问题复现；不参与业务查询路径。
 */
@Data
@TableName("search_trace")
public class SearchTrace {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long conversationId;
    private Long messageId;
    private String query;
    private String intent;
    private Integer ragHit;
    private Double ragScore;
    private String searchKeywords;
    private Integer totalResults;
    private Integer filteredResults;
    private String curationType;
    private String fallbackReason;
    private Integer processingTimeMs;
    private LocalDateTime createdAt;
}
