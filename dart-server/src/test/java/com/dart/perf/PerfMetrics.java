package com.dart.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * 压测延迟样本聚合：P50/P95/P99/均值/QPS。
 */
public class PerfMetrics {

    private final List<Long> samples = new ArrayList<>();

    public void add(long latencyMs) {
        samples.add(latencyMs);
    }

    public int size() {
        return samples.size();
    }

    /** 单次查询耗时（样本均值），调用方已知每样本=1 次查询时用 */
    public double avgMs() {
        if (samples.isEmpty()) {
            return 0;
        }
        return samples.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    public double p50() {
        return percentile(50);
    }

    public double p95() {
        return percentile(95);
    }

    public double p99() {
        return percentile(99);
    }

    public double qps() {
        double avg = avgMs();
        return avg <= 0 ? 0 : 1000.0 / avg;
    }

    private double percentile(int p) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
