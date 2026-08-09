package com.dart.evaluation.model;

import java.util.Map;

/**
 * 单条查询在某一模式下的评估结果。
 */
public record EvalResult(String queryId, String domain, String mode,
                         Map<String, Integer> scores, long latencyMs, boolean ragHit) {

    public double avgScore() {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        return scores.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }
}
