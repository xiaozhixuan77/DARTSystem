package com.dart.evaluation;

import com.dart.evaluation.model.EvalSummary;

import java.util.List;
import java.util.Map;

/**
 * 生成 Markdown 评估报告（总览表 + 分领域明细）。
 */
public class ReportGenerator {

    public static String generate(String runId, Map<String, EvalSummary> byMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("# DART 评估报告\n\n");
        sb.append("运行时间: ").append(runId).append("\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 模式 | 查询数 | 综合 | 相关性 | 准确性 | 完整性 | 清晰度 | 引源 | 平均耗时 | RAG命中率 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalSummary s : byMode.values()) {
            sb.append("| ").append(s.mode())
                    .append(" | ").append(s.count())
                    .append(" | ").append(s.overall())
                    .append(" | ").append(s.avgScores().getOrDefault("relevance", 0.0))
                    .append(" | ").append(s.avgScores().getOrDefault("accuracy", 0.0))
                    .append(" | ").append(s.avgScores().getOrDefault("completeness", 0.0))
                    .append(" | ").append(s.avgScores().getOrDefault("clarity", 0.0))
                    .append(" | ").append(s.avgScores().getOrDefault("sourcing", 0.0))
                    .append(" | ").append(s.avgLatencyMs()).append("ms")
                    .append(" | ").append(String.format("%.1f%%", s.ragHitRate() * 100))
                    .append(" |\n");
        }

        for (EvalSummary s : byMode.values()) {
            if (s.byDomain().isEmpty()) {
                continue;
            }
            sb.append("\n## 分领域明细 — ").append(s.mode()).append("\n\n");
            sb.append("| 领域 | 查询数 | 综合 | 平均耗时 |\n");
            sb.append("|---|---|---|---|\n");
            for (EvalSummary.DomainStat d : s.byDomain()) {
                sb.append("| ").append(d.domain())
                        .append(" | ").append(d.count())
                        .append(" | ").append(d.overall())
                        .append(" | ").append(d.avgLatencyMs()).append("ms |\n");
            }
        }

        sb.append("\n---\n\n> 评分维度：相关性 / 准确性 / 完整性 / 清晰度 / 引源（1-5 分）。\n");
        return sb.toString();
    }
}
