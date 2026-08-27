package com.dart.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class KnowledgeDetailVO {
    private Long id;
    private String title;
    private String content;
    private String sourceUrl;
    private String tags;
    private String notes;
    private Integer scoreT;
    private Integer scoreA;
    private Integer scoreD;
    private Integer scoreM;
    private BigDecimal weightedScore;
    private List<ChunkVO> chunks;
    private List<String> entityNames;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public record ChunkVO(Integer index, String text) {}
}
