package com.dart.evaluation;

import com.dart.evaluation.model.EvalResult;
import com.dart.evaluation.model.EvalSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评估指标统计：五维均分、综合分、平均耗时、RAG 命中率、分领域汇总。
 */
public class MetricsCalculator {

    private static final double ROUND_SCALE = 100.0;

    public static EvalSummary calculate(String mode, List<EvalResult> results) {
        Map<String, Double> avgScores = new LinkedHashMap<>();
        for (String dim : LlmJudge.DIMENSIONS) {
            double avg = results.stream()
                    .mapToInt(r -> r.scores() == null ? 3 : r.scores().getOrDefault(dim, 3))
                    .average().orElse(0.0);
            avgScores.put(dim, round(avg));
        }
        double overall = avgScores.values().stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgLatency = results.stream().mapToLong(EvalResult::latencyMs).average().orElse(0.0);
        double ragHitRate = results.isEmpty() ? 0.0
                : (double) results.stream().filter(EvalResult::ragHit).count() / results.size();

        List<EvalSummary.DomainStat> byDomain = results.stream()
                .collect(Collectors.groupingBy(EvalResult::domain, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new EvalSummary.DomainStat(
                        e.getKey(),
                        e.getValue().size(),
                        round(e.getValue().stream().mapToDouble(EvalResult::avgScore).average().orElse(0.0)),
                        round(e.getValue().stream().mapToLong(EvalResult::latencyMs).average().orElse(0.0))))
                .toList();

        return new EvalSummary(mode, results.size(), avgScores, round(overall),
                round(avgLatency), round(ragHitRate), byDomain);
    }

    private static double round(double v) {
        return Math.round(v * ROUND_SCALE) / ROUND_SCALE;
    }
}
