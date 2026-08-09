package com.dart.evaluation;

import com.dart.evaluation.model.EvalResult;
import com.dart.evaluation.model.EvalSummary;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCalculatorTest {

    private static EvalResult result(String id, String domain, int r, int a, int c, int cl, int s,
                                     long latency, boolean ragHit) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("relevance", r);
        scores.put("accuracy", a);
        scores.put("completeness", c);
        scores.put("clarity", cl);
        scores.put("sourcing", s);
        return new EvalResult(id, domain, "DART-Full", scores, latency, ragHit);
    }

    @Test
    void calculate_emptyResults_returnsZeroBaseline() {
        EvalSummary summary = MetricsCalculator.calculate("DART-Full", List.of());

        assertThat(summary.mode()).isEqualTo("DART-Full");
        assertThat(summary.count()).isZero();
        assertThat(summary.avgScores()).allSatisfy((k, v) -> assertThat(v).isEqualTo(0.0));
        assertThat(summary.overall()).isZero();
    }

    @Test
    void calculate_twoResults_averagesScoresAndLatency() {
        List<EvalResult> results = List.of(
                result("J01", "java-spring", 5, 5, 4, 5, 4, 2000L, false),
                result("J02", "java-spring", 3, 3, 4, 3, 4, 6000L, true));

        EvalSummary summary = MetricsCalculator.calculate("DART-Full", results);

        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.avgScores().get("relevance")).isEqualTo(4.0);
        assertThat(summary.avgScores().get("accuracy")).isEqualTo(4.0);
        assertThat(summary.overall()).isEqualTo(4.0);
        assertThat(summary.avgLatencyMs()).isEqualTo(4000.0);
        assertThat(summary.ragHitRate()).isEqualTo(0.5);
    }

    @Test
    void calculate_groupByDomain() {
        List<EvalResult> results = List.of(
                result("J01", "java-spring", 5, 5, 5, 5, 5, 1000L, false),
                result("J02", "java-spring", 3, 3, 3, 3, 3, 3000L, false),
                result("P01", "python", 4, 4, 4, 4, 4, 2000L, false));

        EvalSummary summary = MetricsCalculator.calculate("DART-Full", results);

        assertThat(summary.byDomain()).hasSize(2);
        EvalSummary.DomainStat java = summary.byDomain().get(0);
        assertThat(java.domain()).isEqualTo("java-spring");
        assertThat(java.count()).isEqualTo(2);
        assertThat(java.overall()).isEqualTo(4.0);
        assertThat(java.avgLatencyMs()).isEqualTo(2000.0);
    }
}
