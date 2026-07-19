package com.dart.agent.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TimelinessScorerTest {

    private TimelinessScorer scorer;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        scorer = new TimelinessScorer(fixedClock);
    }

    @Test
    void calculateT_whenCurrentYear_returns5() {
        assertThat(scorer.score(2026)).isEqualTo(5);
    }

    @Test
    void calculateT_whenOneYearAgo_returns4() {
        assertThat(scorer.score(2025)).isEqualTo(4);
    }

    @Test
    void calculateT_whenTwoYearsAgo_returns3() {
        assertThat(scorer.score(2024)).isEqualTo(3);
    }

    @Test
    void calculateT_whenThreeYearsAgo_returns2() {
        assertThat(scorer.score(2023)).isEqualTo(2);
    }

    @Test
    void calculateT_whenFourYearsAgo_returns1() {
        assertThat(scorer.score(2022)).isEqualTo(1);
    }

    @Test
    void calculateT_whenFiveYearsAgo_returns1() {
        assertThat(scorer.score(2021)).isEqualTo(1);
    }

    @Test
    void calculateT_whenTenYearsAgo_returns1() {
        assertThat(scorer.score(2016)).isEqualTo(1);
    }

    @Test
    void calculateT_whenFutureYear_returns5() {
        assertThat(scorer.score(2030)).isEqualTo(5);
    }

    @Test
    void calculateT_whenYearZero_returns1() {
        assertThat(scorer.score(0)).isEqualTo(1);
    }
}
