package com.dart.perf;

import com.dart.entity.User;
import com.dart.mapper.UserMapper;
import com.dart.service.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 压测数据生成：主题化知识条目（perf-topics.json 30 主题 × 变体模板），
 * 经 embedAndPersist 批量向量化双写（MySQL + Redis），sourceUrl 编码主题前缀供精准度判定。
 */
public class PerfDataGenerator {

    private static final List<String> TABLES = List.of(
            "knowledge_relation", "knowledge_chunk", "knowledge_item", "search_trace", "message",
            "refresh_token", "conversation", "`user`"
    );

    public record Topic(String name, List<String> keywords, String key) {
    }

    private final EmbeddingService embeddingService;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Topic> topics;

    public PerfDataGenerator(EmbeddingService embeddingService, UserMapper userMapper,
                             JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.embeddingService = embeddingService;
        this.userMapper = userMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.topics = loadTopics();
    }

    private List<Topic> loadTopics() {
        try (InputStream in = getClass().getResourceAsStream("/perf-topics.json")) {
            List<com.fasterxml.jackson.databind.JsonNode> nodes = objectMapper.readValue(
                    in, new TypeReference<>() {
                    });
            List<Topic> out = new ArrayList<>();
            int i = 0;
            for (var node : nodes) {
                List<String> keywords = new ArrayList<>();
                node.get("keywords").forEach(k -> keywords.add(k.asText()));
                out.add(new Topic(node.get("topic").asText(), keywords,
                        String.format("p%02d", ++i)));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("加载 perf-topics.json 失败", e);
        }
    }

    public List<Topic> topics() {
        return topics;
    }

    /** 创建压测用户（知识条目归属） */
    public Long ensureUser() {
        User user = new User();
        user.setUsername("perf_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("perf");
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user.getId();
    }

    /** 清空全部业务表 + Redis 向量键（与 BaseIntegrationTest 同序） */
    public void cleanAll() {
        TABLES.forEach(table -> jdbcTemplate.execute("DELETE FROM " + table));
        var keys = redisTemplate.keys("dart:chunk:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        var cacheKeys = redisTemplate.keys("dart:report-cache:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }
    }

    /**
     * 生成 count 个知识条目（主题轮转 + 变体模板），经 embedAndPersist 向量化。
     *
     * @return 实际生成条数
     */
    public int generate(Long userId, int count) {
        int created = 0;
        for (int i = 0; i < count; i++) {
            Topic topic = topics.get(i % topics.size());
            int variant = i / topics.size();
            String title = topic.name() + " 深度解析" + (variant > 0 ? "（" + (variant + 1) + "）" : "");
            String content = buildContent(topic, variant);
            String sourceUrl = "perf://" + topic.key() + "/" + i;
            // scores 不可为 null（embedAndPersist 计算加权分），压测数据用统一评分
            com.dart.agent.scoring.ScoredResource scores = new com.dart.agent.scoring.ScoredResource(
                    sourceUrl, title, content, 4, 4, 4, 4, 4.0);
            try {
                embeddingService.embedAndPersist(userId, title, content, sourceUrl, scores, null);
                created++;
            } catch (Exception e) {
                // 单条失败跳过（API 限流等），报告计数即可
            }
        }
        return created;
    }

    private String buildContent(Topic topic, int variant) {
        List<String> kws = topic.keywords();
        String kw1 = kws.get(variant % kws.size());
        String kw2 = kws.get((variant + 1) % kws.size());
        String kw3 = kws.get((variant + 2) % kws.size());
        String padding = " 补充说明 ".repeat(20 + variant * 10);
        return """
                # %s

                ## 核心概念
                %s 是 %s 领域的关键主题，涉及 %s、%s 等多个方面。

                ## 详细说明
                - 要点一：%s 中的 %s 机制及其在工程中的落地方式
                - 要点二：与 %s 相关的实践方法与常见误区
                - 要点三：%s 的性能特征与最佳实践

                ## 示例
                %s 的典型应用场景包括架构设计、系统优化与工程落地。
                从基础概念到进阶用法，掌握 %s 需要理解其底层原理与生态工具。
                %s
                """.formatted(
                topic.name(), topic.name(), topic.name(), kw1, kw2,
                topic.name(), kw1, kw2, kw3,
                topic.name(), topic.name(), padding);
    }
}
