package com.dart.agent.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
                          double weightT, double weightA, double weightD, double weightM) {
        this.url = url;
        this.title = title;
        this.content = content;
        this.scoreT = scoreT;
        this.scoreA = scoreA;
        this.scoreD = scoreD;
        this.scoreM = scoreM;
        this.weightedScore = round(scoreT * weightT + scoreA * weightA + scoreD * weightD + scoreM * weightM);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public int getScoreT() { return scoreT; }
    public int getScoreA() { return scoreA; }
    public int getScoreD() { return scoreD; }
    public int getScoreM() { return scoreM; }
    public double getWeightedScore() { return weightedScore; }
}
