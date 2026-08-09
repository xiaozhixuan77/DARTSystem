package com.dart.evaluation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    @Test
    void generate_includesOverviewTableAndDomains() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("relevance", 4);
        scores.put("accuracy", 4);
        scores.put("completeness", 4);
        scores.put("clarity", 4);
        scores.put("sourcing", 4);
        Map<String, Double> avg = new LinkedHashMap<>();
        avg.put("relevance", 4.0);
        avg.put("accuracy", 4.0);
        avg.put("completeness", 4.0);
        avg.put("clarity", 4.0);
        avg.put("sourcing", 4.0);

        EvalSummary summary = new EvalSummary("DART-Full", 10, avg, 4.0, 6800.0, 0.3,
                List.of(new EvalSummary.DomainStat("java-spring", 10, 4.0, 6800.0)));

        String markdown = ReportGenerator.generate("2026-08-09T10:00", Map.of("DART-Full", summary));

        assertThat(markdown).contains("# DART 评估报告");
        assertThat(markdown).contains("DART-Full");
        assertThat(markdown).contains("| 模式 | 查询数 | 综合");
        assertThat(markdown).contains("10");
        assertThat(markdown).contains("30.0%");
        assertThat(markdown).contains("java-spring");
    }

    @Test
    void generate_multipleModes_listsAllRows() {
        Map<String, EvalSummary> byMode = new LinkedHashMap<>();
        for (String mode : List.of("RAG-Only", "Search-Only", "DART-Full")) {
            Map<String, Double> avg = new LinkedHashMap<>();
            for (String dim : LlmJudge.DIMENSIONS) {
                avg.put(dim, 3.5);
            }
            byMode.put(mode, new EvalSummary(mode, 2, avg, 3.5, 1000.0, 0.0, List.of()));
        }

        String markdown = ReportGenerator.generate("run-1", byMode);

        assertThat(markdown).contains("RAG-Only");
        assertThat(markdown).contains("Search-Only");
        assertThat(markdown).contains("DART-Full");
    }
}
