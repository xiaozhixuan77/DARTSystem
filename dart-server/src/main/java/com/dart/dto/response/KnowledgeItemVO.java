package com.dart.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class KnowledgeItemVO {
    private Long id;
    private String title;
    private String sourceUrl;
    private String tags;
    private Integer scoreT;
    private Integer scoreA;
    private Integer scoreD;
    private Integer scoreM;
    private BigDecimal weightedScore;
    private LocalDateTime createdAt;
}
