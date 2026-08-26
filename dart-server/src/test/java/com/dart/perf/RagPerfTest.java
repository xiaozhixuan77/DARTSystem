package com.dart.perf;

import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.UserMapper;
import com.dart.perf.PerfAiConfig.SwitchableEmbeddingModel;
import com.dart.perf.PerfReportWriter.AccuracyRow;
import com.dart.perf.PerfReportWriter.SpeedRow;
import com.dart.perf.RedisVectorSearcher.ChunkHit;
import com.dart.service.EmbeddingService;
import com.dart.service.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 知识库压力测试（真库，RUN_PERF=true 门控）：
 *   RUN_PERF=true mvn test -Dtest=RagPerfTest            # 完整两阶段（约 5 分钟）
 *   RUN_PERF=true mvn test -Dtest=RagPerfTest -Dperf.fast=true   # 仅速度阶段（约 2 分钟）
 *
 * 速度阶段：mock 向量，梯度 200/1000/3000 条目，MySQL 全量扫描 vs Redis HNSW 对比。
 * 精准度阶段：真实 Embedding（SILICONFLOW_API_KEY），150 条目 × 30 查询 ground truth 命中率。
 * 数据隔离：dart_test 库（schema.sql 每次重建）+ 专用 perf 用户 + 结束后清库。
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("perf")
@Import(PerfAiConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_PERF", matches = "true")
class RagPerfTest {

    private static final int WARMUP = 20;
    private static final int QUERY_REPEAT = 10;
    private static final int ACCURACY_ITEMS = 150;   // 30 主题 × 5 条目
    private static final int ACCURACY_TOPIC_ITEMS = 5;

    @Autowired
    private RagService ragService;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Autowired
    private KnowledgeItemMapper knowledgeItemMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private EmbeddingModel embeddingModel;
    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    @Value("${agent.rag.similarity-threshold:0.60}")
    private double threshold;
    @Value("${agent.rag.top-k:5}")
    private int topK;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PerfDataGenerator generator;
    private RedisVectorSearcher redisSearcher;

    private record PerfQuery(String id, String query, String topic) {
    }

    @Test
    void runPerf() throws Exception {
        generator = new PerfDataGenerator(embeddingService, userMapper, jdbcTemplate, redisTemplate);
        redisSearcher = new RedisVectorSearcher(redisTemplate.getConnectionFactory());
        redisSearcher.ensureIndex();

        List<PerfQuery> queries = loadQueries();
        PerfReportWriter report = new PerfReportWriter();
        boolean realApi = ((SwitchableEmbeddingModel) embeddingModel).isRealApiAvailable();
        report.overview(LocalDateTime.now(), "本机（Windows 11 + 本地 Docker MySQL/Redis Stack）",
                realApi ? "已注入（硅基流动 BAAI/bge-m3）" : "未注入（仅速度阶段）",
                500, topK, threshold);

        // 一、速度阶段（mock 向量）
        List<SpeedRow> speedRows = runSpeedPhase(queries);
        report.speedSection(speedRows);

        // 二、精准度阶段（真实 embedding）
        List<AccuracyRow> accuracyRows = runAccuracyPhase(queries);
        report.accuracySection(accuracyRows);

        // 三、结论
        report.conclusion(buildConclusion(speedRows, accuracyRows, realApi));

        String path = report.write();
        System.out.println("\n压测完成，报告: " + path + "\n");
        assertTrue(java.nio.file.Files.size(java.nio.file.Path.of(path)) > 0, "压测报告应生成");
    }

    // === 速度阶段 ===

    private List<SpeedRow> runSpeedPhase(List<PerfQuery> queries) throws Exception {
        boolean fast = Boolean.parseBoolean(System.getProperty("perf.fast", "false"));
        int[] gradients = fast ? new int[]{200, 1000} : new int[]{200, 1000, 3000};
        List<PerfQuery> speedQueries = queries.subList(0, Math.min(20, queries.size()));
        List<SpeedRow> rows = new ArrayList<>();

        ((SwitchableEmbeddingModel) embeddingModel).setMockMode(true);
        for (int count : gradients) {
            System.out.println(">>> 速度阶段梯度: " + count + " 条目");
            generator.cleanAll();
            Long userId = generator.ensureUser();
            int created = generator.generate(userId, count);
            Long chunkCount = knowledgeChunkMapper.selectCount(null);
            float[] probeVec = embeddingService.embed(speedQueries.getFirst().query());

            // 预热（JIT）
            for (int i = 0; i < WARMUP; i++) {
                ragService.retrieve(speedQueries.get(i % speedQueries.size()).query(), userId);
                redisSearcher.search(probeVec, topK);
            }

            // MySQL 全量扫描路径
            PerfMetrics mysql = new PerfMetrics();
            for (PerfQuery q : speedQueries) {
                for (int r = 0; r < QUERY_REPEAT; r++) {
                    long start = System.nanoTime();
                    ragService.retrieve(q.query(), userId);
                    mysql.add((System.nanoTime() - start) / 1_000_000);
                }
            }
            rows.add(new SpeedRow(count, chunkCount.intValue(), "MySQL 全量扫描",
                    mysql.p50(), mysql.p95(), mysql.p99(), mysql.avgMs(), mysql.qps()));

            // Redis HNSW 路径
            PerfMetrics redis = new PerfMetrics();
            for (PerfQuery q : speedQueries) {
                float[] vec = embeddingService.embed(q.query());
                for (int r = 0; r < QUERY_REPEAT; r++) {
                    long start = System.nanoTime();
                    redisSearcher.search(vec, topK);
                    redis.add((System.nanoTime() - start) / 1_000_000);
                }
            }
            rows.add(new SpeedRow(count, chunkCount.intValue(), "Redis HNSW",
                    redis.p50(), redis.p95(), redis.p99(), redis.avgMs(), redis.qps()));
        }
        return rows;
    }

    // === 精准度阶段 ===

    private List<AccuracyRow> runAccuracyPhase(List<PerfQuery> queries) throws Exception {
        if (!((SwitchableEmbeddingModel) embeddingModel).isRealApiAvailable()) {
            return List.of();
        }
        System.out.println(">>> 精准度阶段: " + ACCURACY_ITEMS + " 条目真实 Embedding");
        ((SwitchableEmbeddingModel) embeddingModel).setMockMode(false);
        generator.cleanAll();
        Long userId = generator.ensureUser();
        generator.generate(userId, ACCURACY_ITEMS);
        System.out.println("[DEBUG] 精准度造数 chunk=" + knowledgeChunkMapper.selectCount(null)
                + " Redis num_docs=" + redisSearcher.indexDocCount()
                + " FT.SEARCH 全量=" + redisSearcher.countAll());

        List<AccuracyRow> rows = new ArrayList<>();
        rows.add(measureAccuracy("MySQL 全量扫描", queries, userId, true));
        rows.add(measureAccuracy("Redis HNSW", queries, userId, false));
        return rows;
    }

    private AccuracyRow measureAccuracy(String path, List<PerfQuery> queries,
                                        Long userId, boolean mysqlPath) {
        int hitCount = 0;      // 查询数（命中 ≥1 个 ground truth）
        int top1Count = 0;     // 首个结果即命中
        double mrrSum = 0;
        double similaritySum = 0;
        int similarityCount = 0;
        int thresholdPass = 0;

        for (PerfQuery q : queries) {
            String topicKey = topicKeyOf(q.topic());
            List<Double> sims = new ArrayList<>();
            if (mysqlPath) {
                var result = ragService.retrieve(q.query(), userId);
                if (result.isEmpty()) {
                    continue;
                }
                var rag = result.get();
                List<String> urls = rag.sourceUrls();
                sims = new ArrayList<>(urls.size());
                for (int i = 0; i < urls.size(); i++) {
                    if (urls.get(i).startsWith("perf://" + topicKey + "/")) {
                        sims.add((double) i);
                    }
                }
                if (rag.maxSimilarity() >= threshold) {
                    thresholdPass++;
                }
                similaritySum += Math.max(rag.maxSimilarity(), 0);
                similarityCount++;
            } else {
                float[] vec = embeddingService.embed(q.query());
                List<ChunkHit> hits = redisSearcher.search(vec, topK);
                if (q.id().equals("q01")) {
                    System.out.println("[DEBUG] Redis 检索 q01 hits=" + hits.size()
                            + " 首条=" + (hits.isEmpty() ? "无" : hits.getFirst().itemId()));
                }
                Map<Long, String> urls = loadItemUrls(hits);
                sims = new ArrayList<>(hits.size());
                for (int i = 0; i < hits.size(); i++) {
                    String url = urls.get(hits.get(i).itemId());
                    if (url != null && url.startsWith("perf://" + topicKey + "/")) {
                        sims.add((double) i);
                    }
                    double s = hits.get(i).similarity();
                    if (s >= 0) {
                        similaritySum += s;
                        similarityCount++;
                        if (s >= threshold) {
                            thresholdPass++;
                        }
                    }
                }
            }
            if (!sims.isEmpty()) {
                hitCount++;
                if (sims.getFirst() == 0) {
                    top1Count++;
                }
                mrrSum += 1.0 / (sims.getFirst() + 1);
            }
        }

        int total = queries.size();
        return new AccuracyRow(path,
                (double) hitCount / total,
                (double) top1Count / total,
                mrrSum / total,
                similarityCount == 0 ? 0 : similaritySum / similarityCount,
                (double) thresholdPass / Math.max(similarityCount, 1));
    }

    private Map<Long, String> loadItemUrls(List<ChunkHit> hits) {
        Set<Long> ids = hits.stream().map(ChunkHit::itemId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new java.util.HashMap<>();
        for (com.dart.entity.KnowledgeItem item : knowledgeItemMapper.selectBatchIds(ids)) {
            if (item != null) {
                map.put(item.getId(), item.getSourceUrl());
            }
        }
        return map;
    }

    private String topicKeyOf(String topicName) {
        return generator.topics().stream()
                .filter(t -> t.name().equals(topicName))
                .findFirst()
                .map(PerfDataGenerator.Topic::key)
                .orElse(null);
    }

    // === 工具 ===

    private List<PerfQuery> loadQueries() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/perf-queries.json")) {
            List<Map<String, String>> nodes = objectMapper.readValue(in, new TypeReference<>() {
            });
            return nodes.stream()
                    .map(n -> new PerfQuery(n.get("id"), n.get("query"), n.get("topic")))
                    .toList();
        }
    }

    private String buildConclusion(List<SpeedRow> rows, List<AccuracyRow> acc, boolean realApi) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **速度**：MySQL 全量扫描延迟随 chunk 数线性增长（O(N) 解码+余弦），"
                + "最后一梯度可对比 MySQL 与 Redis HNSW 的 P95/均值差距。\n");
        if (!rows.isEmpty()) {
            SpeedRow lastMysql = rows.isEmpty() ? null : rows.stream()
                    .filter(r -> r.path().startsWith("MySQL")).reduce((a, b) -> b).orElse(null);
            SpeedRow lastRedis = rows.stream()
                    .filter(r -> r.path().startsWith("Redis")).reduce((a, b) -> b).orElse(null);
            if (lastMysql != null && lastRedis != null) {
                sb.append("- 规模 ").append(lastMysql.items()).append(" 条目 / ")
                        .append(lastMysql.chunks()).append(" chunk：MySQL 均值 ")
                        .append(String.format("%.1fms", lastMysql.avg()))
                        .append(" vs Redis 均值 ").append(String.format("%.1fms", lastRedis.avg()))
                        .append("（QPS ").append(String.format("%.1f", lastMysql.qps()))
                        .append(" / ").append(String.format("%.1f", lastRedis.qps())).append("）。\n");
            }
        }
        if (realApi && !acc.isEmpty()) {
            AccuracyRow mysql = acc.get(0);
            sb.append("- **精准度**：MySQL 路径 Recall@5 ")
                    .append(String.format("%.1f%%", mysql.recallAt5() * 100))
                    .append("、Top1 ").append(String.format("%.1f%%", mysql.top1() * 100))
                    .append("、MRR ").append(String.format("%.3f", mysql.mrr()))
                    .append("；Redis HNSW 与全量扫描一致（KNN 精确检索）则两路径指标应接近。\n");
        }
        sb.append("- **优化建议**：若 MySQL 路径 P95 超 200ms，优先切换 Redis HNSW 为主检索路径"
                + "（MySQL 兜底对账）；chunk 向量解码可缓存；超大知识库可降 top-k 或提高阈值。");
        return sb.toString();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        // 用例结束后清库，防残留影响其他测试（dart_test 库由 schema.sql 管理）
        if (generator != null) {
            generator.cleanAll();
        }
    }
}
