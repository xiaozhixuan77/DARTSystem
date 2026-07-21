package com.dart.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识条目列表项 VO。
 */
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Integer getScoreT() { return scoreT; }
    public void setScoreT(Integer scoreT) { this.scoreT = scoreT; }
    public Integer getScoreA() { return scoreA; }
    public void setScoreA(Integer scoreA) { this.scoreA = scoreA; }
    public Integer getScoreD() { return scoreD; }
    public void setScoreD(Integer scoreD) { this.scoreD = scoreD; }
    public Integer getScoreM() { return scoreM; }
    public void setScoreM(Integer scoreM) { this.scoreM = scoreM; }
    public BigDecimal getWeightedScore() { return weightedScore; }
    public void setWeightedScore(BigDecimal weightedScore) { this.weightedScore = weightedScore; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
