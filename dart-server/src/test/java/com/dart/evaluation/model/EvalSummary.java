package com.dart.evaluation.model;

import java.util.List;
import java.util.Map;

/**
 * 模式汇总：五维均分 + 综合 + 平均耗时 + RAG 命中率 + 分领域统计。
 */
public record EvalSummary(String mode, int count, Map<String, Double> avgScores,
                          double overall, double avgLatencyMs, double ragHitRate,
                          List<DomainStat> byDomain) {

    public record DomainStat(String domain, int count, double overall, double avgLatencyMs) {}
}
