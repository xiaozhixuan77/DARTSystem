package com.dart.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 压测报告生成：markdown 输出到 target/perf/report-{timestamp}.md。
 * 结构：总览 → 速度表（MySQL vs Redis 各梯度延迟）→ 精准度表 → 结论。
 */
public class PerfReportWriter {

    public record SpeedRow(int items, int chunks, String path,
                           double p50, double p95, double p99, double avg, double qps) {
    }

    public record AccuracyRow(String path, double recallAt5, double top1, double mrr,
                              double avgSimilarity, double thresholdPassRate) {
    }

    private final StringBuilder sb = new StringBuilder();

    public void overview(LocalDateTime time, String env, String apiKeyStatus,
                         int chunkSize, int topK, double threshold) {
        sb.append("# RAG 知识库压力测试报告\n\n");
        sb.append("| 项 | 值 |\n|:---|:---|\n");
        sb.append("| 执行时间 | ").append(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(" |\n");
        sb.append("| 环境 | ").append(env).append(" |\n");
        sb.append("| 真实 Embedding | ").append(apiKeyStatus).append(" |\n");
        sb.append("| 分块 | ").append(chunkSize).append(" 字符 / ").append("重叠 50 |\n");
        sb.append("| 检索 top-k | ").append(topK).append(" |\n");
        sb.append("| 命中阈值 | ").append(threshold).append(" |\n\n");
    }

    public void speedSection(List<SpeedRow> rows) {
        sb.append("## 一、检索速度（延迟 ms / 吞吐 QPS）\n\n");
        sb.append("| 条目数 | chunk 数 | 检索路径 | P50 | P95 | P99 | 均值 | QPS |\n");
        sb.append("|:---|:---|:---|:---|:---|:---|:---|:---|\n");
        for (SpeedRow r : rows) {
            sb.append(String.format("| %d | %d | %s | %.1f | %.1f | %.1f | %.1f | %.1f |\n",
                    r.items(), r.chunks(), r.path(), r.p50(), r.p95(), r.p99(), r.avg(), r.qps()));
        }
        sb.append("\n");
    }

    public void accuracySection(List<AccuracyRow> rows) {
        sb.append("## 二、检索精准度（ground truth 主题命中）\n\n");
        if (rows.isEmpty()) {
            sb.append("> 未执行：缺少 SILICONFLOW_API_KEY 环境变量（真实 Embedding 不可用），"
                    + "仅速度阶段可测。设置环境变量后重跑完整压测。\n\n");
            return;
        }
        sb.append("| 检索路径 | Recall@5 | Top1 命中 | MRR | 平均相似度 | 阈值(≥0.60)通过率 |\n");
        sb.append("|:---|:---|:---|:---|:---|:---|\n");
        for (AccuracyRow r : rows) {
            sb.append(String.format("| %s | %.1f%% | %.1f%% | %.3f | %.3f | %.1f%% |\n",
                    r.path(), r.recallAt5() * 100, r.top1() * 100, r.mrr(),
                    r.avgSimilarity(), r.thresholdPassRate() * 100));
        }
        sb.append("\n");
    }

    public void conclusion(String text) {
        sb.append("## 三、结论与优化建议\n\n").append(text).append("\n");
    }

    public String write() throws IOException {
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = Path.of("target/perf/report-" + runId + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        return out.toAbsolutePath().toString();
    }
}
