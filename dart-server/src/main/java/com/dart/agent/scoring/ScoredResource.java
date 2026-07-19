package com.dart.agent.scoring;

import java.util.Objects;

/**
 * 评分结果 —— 包含四维分项和加权总分。
 */
public class ScoredResource {

    private final String url;
    private final String title;
    private final String content;
    private final int scoreT;
    private final int scoreA;
    private final int scoreD;
    private final int scoreM;
    private final double weightedScore;

    public ScoredResource(String url, String title, String content,
                          int scoreT, int scoreA, int scoreD, int scoreM,
                          double weightedScore) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.scoreT = scoreT;
        this.scoreA = scoreA;
        this.scoreD = scoreD;
        this.scoreM = scoreM;
        this.weightedScore = weightedScore;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public int getScoreT() { return scoreT; }
    public int getScoreA() { return scoreA; }
    public int getScoreD() { return scoreD; }
    public int getScoreM() { return scoreM; }
    public double getWeightedScore() { return weightedScore; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScoredResource that)) return false;
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    @Override
    public String toString() {
        return "ScoredResource{url='%s', title='%s', S=%.2f (T=%d/A=%d/D=%d/M=%d)}"
                .formatted(url, title, weightedScore, scoreT, scoreA, scoreD, scoreM);
    }
}
