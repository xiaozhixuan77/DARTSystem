package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityScorerTest {

    private static final double WT = 0.15;
    private static final double WA = 0.30;
    private static final double WD = 0.20;
    private static final double WM = 0.35;

    private QualityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new QualityScorer(WT, WA, WD, WM);
    }

    @Test
    void score_whenAllMax_returns5() {
        assertThat(scorer.score(5, 5, 5, 5)).isEqualTo(5.00);
    }

    @Test
    void score_whenAllMin_returns1() {
        assertThat(scorer.score(1, 1, 1, 1)).isEqualTo(1.00);
    }

    @Test
    void score_whenOfficialDocHighMatch_returnsHigh() {
        assertThat(scorer.score(5, 5, 4, 5)).isEqualTo(4.80);
    }

    @Test
    void score_whenOldBlogLowMatch_returnsLow() {
        assertThat(scorer.score(1, 2, 2, 1)).isEqualTo(1.50);
    }

    @Test
    void score_whenThresholdBoundary_returns3() {
        assertThat(scorer.score(3, 3, 3, 3)).isEqualTo(3.00);
    }

    @Test
    void score_weightEmphasizesAuthorityAndMatch() {
        double contributionA = WA * 5; // 1.50
        double contributionD = WD * 5; // 1.00
        double contributionT = WT * 5; // 0.75
        assertThat(contributionA).isGreaterThan(contributionD);
        assertThat(contributionD).isGreaterThan(contributionT);
    }

    @Test
    void score_returnsTwoDecimals() {
        assertThat(scorer.score(3, 4, 3, 2)).isEqualTo(2.95);
    }

    @Test
    void isAboveThreshold_whenScoreAboveThreshold_returnsTrue() {
        assertThat(scorer.isAboveThreshold(4.0)).isTrue();
    }

    @Test
    void isAboveThreshold_whenScoreEqualsThreshold_returnsTrue() {
        assertThat(scorer.isAboveThreshold(3.0)).isTrue();
    }

    @Test
    void isAboveThreshold_whenScoreBelowThreshold_returnsFalse() {
        assertThat(scorer.isAboveThreshold(2.99)).isFalse();
    }

    @Test
    void score_createsScoredResource_withCorrectFields() {
        ScoredResource result = scorer.evaluate(
                "https://spring.io/guides", "Spring Boot Guide",
                "Spring Boot makes it easy...",
                5, 5, 4, 5);

        assertThat(result.getUrl()).isEqualTo("https://spring.io/guides");
        assertThat(result.getTitle()).isEqualTo("Spring Boot Guide");
        assertThat(result.getScoreT()).isEqualTo(5);
        assertThat(result.getScoreA()).isEqualTo(5);
        assertThat(result.getScoreD()).isEqualTo(4);
        assertThat(result.getScoreM()).isEqualTo(5);
        assertThat(result.getWeightedScore()).isEqualTo(4.80);
    }
}
