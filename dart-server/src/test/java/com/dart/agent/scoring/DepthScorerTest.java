package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepthScorerTest {

    private DepthScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new DepthScorer();
    }

    @Test
    void calculateD_whenShallowArticle_returns1() {
        assertThat(scorer.score(100, 0, false, false)).isEqualTo(1);
    }

    @Test
    void calculateD_whenMinimalArticle_returns1() {
        assertThat(scorer.score(500, 0, false, false)).isEqualTo(1);
    }

    @Test
    void calculateD_whenMediumArticle_returns2() {
        assertThat(scorer.score(800, 1, false, false)).isEqualTo(2);
    }

    @Test
    void calculateD_whenRichArticle_returnsMid() {
        assertThat(scorer.score(1500, 2, true, false)).isEqualTo(5);
    }

    @Test
    void calculateD_whenFullFeatures_returns5() {
        assertThat(scorer.score(2000, 3, true, true)).isEqualTo(5);
    }

    @Test
    void calculateD_whenZeroContent_returns0() {
        assertThat(scorer.score(0, 0, false, false)).isEqualTo(0);
    }

    @Test
    void calculateD_whenManyCodeBlocks_capsAt5() {
        assertThat(scorer.score(500, 10, false, false)).isEqualTo(5);
    }

    @Test
    void calculateD_whenOnlyDiagramsNoText_returns2() {
        assertThat(scorer.score(0, 0, true, true)).isEqualTo(2);
    }
}
