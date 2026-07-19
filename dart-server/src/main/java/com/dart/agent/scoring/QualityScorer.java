package com.dart.agent.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 四维评分门面：S = 0.15×T + 0.30×A + 0.20×D + 0.35×M
 * 默认阈值 3.0。
 */
public class QualityScorer {

    private final double weightT;
    private final double weightA;
    private final double weightD;
    private final double weightM;
    private final double threshold;

    public QualityScorer(double weightT, double weightA, double weightD, double weightM) {
        this(weightT, weightA, weightD, weightM, 3.0);
    }

    public QualityScorer(double weightT, double weightA, double weightD, double weightM, double threshold) {
        this.weightT = weightT;
        this.weightA = weightA;
        this.weightD = weightD;
        this.weightM = weightM;
        this.threshold = threshold;
    }

    public double score(int t, int a, int d, int m) {
        return round(t * weightT + a * weightA + d * weightD + m * weightM);
    }

    public boolean isAboveThreshold(double score) {
        return score >= threshold;
    }

    public ScoredResource evaluate(String url, String title, String content,
                                    int t, int a, int d, int m) {
        return new ScoredResource(url, title, content, t, a, d, m, weightT, weightA, weightD, weightM);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
