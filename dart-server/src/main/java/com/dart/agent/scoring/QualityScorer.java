package com.dart.agent.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 四维评分门面：S = 0.15×T + 0.30×A + 0.20×D + 0.35×M
 * 默认阈值 3.0。
 */
public class QualityScorer {

    private static final Logger log = LoggerFactory.getLogger(QualityScorer.class);

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
        validateWeights();
    }

    private void validateWeights() {
        double sum = weightT + weightA + weightD + weightM;
        if (Math.abs(sum - 1.0) > 0.01) {
            log.warn("评分权重之和偏离 1.0: wT={}, wA={}, wD={}, wM={} (sum={})",
                    weightT, weightA, weightD, weightM, sum);
        }
    }

    /**
     * 计算加权总分，精确到两位小数。
     */
    public double score(int t, int a, int d, int m) {
        double value = t * weightT + a * weightA + d * weightD + m * weightM;
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public boolean isAboveThreshold(double score) {
        return score >= threshold;
    }

    /**
     * 对页面进行四维评分并生成 ScoredResource。
     */
    public ScoredResource evaluate(String url, String title, String content,
                                    int t, int a, int d, int m) {
        double ws = score(t, a, d, m);
        return new ScoredResource(url, title, content, t, a, d, m, ws);
    }
}
