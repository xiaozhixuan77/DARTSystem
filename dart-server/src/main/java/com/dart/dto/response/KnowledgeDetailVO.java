package com.dart.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识条目详情 VO（含分块列表）。
 */
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public record ChunkVO(Integer index, String text) {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
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
    public List<ChunkVO> getChunks() { return chunks; }
    public void setChunks(List<ChunkVO> chunks) { this.chunks = chunks; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
