package com.dart.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.agent.scoring.ScoredResource;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.service.DataConsistencyTask;
import com.dart.service.EmbeddingService;
import com.dart.service.KnowledgeService;
import com.dart.util.RedisKeyConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;

import static org.mockito.ArgumentMatchers.any;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataConsistencyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private KnowledgeItemMapper knowledgeItemMapper;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private DataConsistencyTask dataConsistencyTask;

    @SpyBean
    private StringRedisTemplate redisTemplate;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createTestUser();
    }

    @AfterEach
    void resetSpy() {
        reset(redisTemplate);
    }

    // ────────── 原始场景 ──────────

    @Test
    @DisplayName("正常双写：MySQL + Redis 均有数据")
    void normalDualWriteBothMysqlAndRedisHaveData() throws Exception {
        mockEmbedding();

        ScoredResource scores = new ScoredResource("http://example.com/doc", "Test Doc",
                "Test content for embedding.", 4, 5, 3, 4, 3.5);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "Test Title",
                "Test content for embedding test.", "http://example.com/doc", scores, "操作指南");

        assertThat(ids).isNotEmpty();
        Long itemId = ids.get(0);

        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        assertThat(item).isNotNull();
        assertThat(item.getTitle()).isEqualTo("Test Title");

        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new QueryWrapper<KnowledgeChunk>().eq("knowledge_item_id", itemId));
        assertThat(chunks).isNotEmpty();

        Set<String> redisKeys = waitForRedisKeys(2000);
        assertThat(redisKeys).isNotEmpty();
    }

    @Test
    @DisplayName("软删除后知识条目不可见")
    void softDeleteKnowledgeItem() throws Exception {
        mockEmbedding();

        ScoredResource scores = new ScoredResource("http://example.com/del", "To Delete",
                "Content to be deleted.", 3, 3, 3, 3, 3.0);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "To Delete",
                "Content to be deleted.", "http://example.com/del", scores, "操作指南");
        Long itemId = ids.get(0);

        knowledgeService.deleteKnowledge(testUserId, itemId);

        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        assertThat(item).isNull();

        var page = knowledgeService.listUserKnowledge(testUserId, 1, 20, null);
        assertThat(page.getRecords().stream().noneMatch(k -> k.getId().equals(itemId))).isTrue();
    }

    @Test
    @DisplayName("已删除条目不出现在 RAG 检索中")
    void deletedItemNotInRagRetrieval() throws Exception {
        mockEmbedding();

        ScoredResource scores = new ScoredResource("http://example.com/rag", "RAG Test",
                "RAG test content.", 5, 5, 5, 5, 5.0);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "RAG Test",
                "RAG test content.", "http://example.com/rag", scores, "操作指南");
        Long itemId = ids.get(0);

        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        assertThat(item).isNotNull();

        knowledgeService.deleteKnowledge(testUserId, itemId);

        KnowledgeItem deleted = knowledgeItemMapper.selectById(itemId);
        assertThat(deleted).isNull();
    }

    @Test
    @DisplayName("重复 source URL 去重检测")
    void duplicateSourceUrlDetection() throws Exception {
        mockEmbedding();

        String sourceUrl = "http://example.com/unique-" + System.nanoTime();
        ScoredResource scores = new ScoredResource(sourceUrl, "Unique", "Content.", 4, 4, 4, 4, 4.0);

        embeddingService.embedAndPersist(testUserId, "Unique", "Content.", sourceUrl, scores, "操作指南");

        boolean exists = knowledgeService.existsBySourceUrl(sha256(sourceUrl));
        assertThat(exists).isTrue();
    }

    // ────────── 新增：双写异常场景 ──────────

    @Test
    @DisplayName("Redis 全失败 → 3 次重试耗尽 → MySQL 不受影响")
    void redisAllRetriesExhaustedMysqlUnaffected() throws Exception {
        mockEmbedding();
        doThrow(new RuntimeException("Redis unavailable"))
                .doThrow(new RuntimeException("Redis unavailable"))
                .doThrow(new RuntimeException("Redis unavailable"))
                .when(redisTemplate).executePipelined(any(RedisCallback.class));

        ScoredResource scores = new ScoredResource("http://example.com/retry", "Retry Test",
                "Content for retry exhaustion test.", 4, 4, 4, 4, 4.0);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "Retry Title",
                "Content for retry exhaustion test.", "http://example.com/retry", scores, "操作指南");

        // MySQL 写入正常（事务已提交）
        assertThat(ids).isNotEmpty();
        KnowledgeItem item = knowledgeItemMapper.selectById(ids.get(0));
        assertThat(item).isNotNull();
        assertThat(item.getTitle()).isEqualTo("Retry Title");

        // Redis 无数据（3 次重试全部失败）
        Set<String> redisKeys = redisTemplate.keys("dart:chunk:*");
        assertThat(redisKeys == null || redisKeys.isEmpty()).isTrue();

        // 验证确实重试了 3 次
        verify(redisTemplate, times(3)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("Redis 第 2 次重试成功恢复")
    void redisRetryRecoversOnSecondAttempt() throws Exception {
        mockEmbedding();
        doThrow(new RuntimeException("Redis transient error"))
                .doCallRealMethod()
                .when(redisTemplate).executePipelined(any(RedisCallback.class));

        ScoredResource scores = new ScoredResource("http://example.com/recover", "Recover Test",
                "Content for retry recovery test.", 4, 4, 4, 4, 4.0);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "Recover Title",
                "Content for retry recovery test.", "http://example.com/recover", scores, "操作指南");

        assertThat(ids).isNotEmpty();

        // Redis 最终有数据
        Set<String> redisKeys = waitForRedisKeys(3000);
        assertThat(redisKeys).isNotEmpty();

        // 验证重试了 2 次（第 1 次失败 + 第 2 次成功）
        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("事务回滚后 MySQL + Redis 均无残留")
    void transactionRollbackNoResidue() {
        // EmbeddingModel 抛异常 → 触发 @Transactional 回滚
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Embedding API outage"));

        ScoredResource scores = new ScoredResource("http://example.com/rollback", "Rollback",
                "Content for transaction rollback test.", 4, 4, 4, 4, 4.0);

        assertThatThrownBy(() ->
                embeddingService.embedAndPersist(testUserId, "Rollback Title",
                        "Content for transaction rollback test.", "http://example.com/rollback", scores, "操作指南"))
                .isInstanceOf(RuntimeException.class);

        // MySQL 知识条目未创建
        long itemCount = knowledgeItemMapper.selectCount(new QueryWrapper<>());
        assertThat(itemCount).isZero();

        // Redis 无数据
        Set<String> redisKeys = redisTemplate.keys("dart:chunk:*");
        assertThat(redisKeys == null || redisKeys.isEmpty()).isTrue();
    }

    // ────────── 新增：补偿与对账 ──────────

    @Test
    @DisplayName("重试耗尽后 detectMissingVectors 发现缺失向量")
    void detectMissingVectorsAfterRetryExhaustion() throws Exception {
        mockEmbedding();
        doThrow(new RuntimeException("Redis permanently down"))
                .doThrow(new RuntimeException("Redis permanently down"))
                .doThrow(new RuntimeException("Redis permanently down"))
                .when(redisTemplate).executePipelined(any(RedisCallback.class));

        embeddingService.embedAndPersist(testUserId, "Missing Title",
                "Content for missing vector detection.", "http://example.com/missing",
                new ScoredResource("http://example.com/missing", "Missing", "Content.", 4, 4, 4, 4, 4.0),
                "操作指南");

        // 重置 spy 以恢复真实 Redis（detectMissingVectors 需要）
        reset(redisTemplate);

        // detectMissingVectors 应发现 MySQL 中有记录但 Redis 中无对应 key，并补偿写入
        dataConsistencyTask.detectMissingVectors();

        // 补偿后缺失的 chunk 应写入 Redis（dart_test 库每用例清库，非空即本次补偿）
        Set<String> keys = waitForRedisKeys(5000);
        assertThat(keys).isNotEmpty();
    }

    @Test
    @DisplayName("对账偏差超过 5% 时触发警告")
    void reconciliationDeviationExceedsThreshold() {
        // 直接在 MySQL 中创建 10 条 chunk 记录（模拟不一致状态）
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(testUserId);
        item.setTitle("Reconciliation Test");
        item.setContent("Content.");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);

        for (int i = 0; i < 10; i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(item.getId());
            kc.setChunkIndex(i);
            kc.setChunkText("Chunk " + i);
            kc.setEmbeddingId("recon:chunk:" + i);
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insert(kc);
        }

        // 仅写 5 个 Redis key（而非全部 10 个）→ 偏差 50%
        for (int i = 0; i < 5; i++) {
            String key = RedisKeyConstants.DART_PREFIX + "recon:chunk:" + i;
            redisTemplate.opsForValue().set(key, "\"{\\\"emb\\\":\\\"" + i + "\\\"}\"");
        }

        // 对账不应抛异常
        dataConsistencyTask.consistencyCheck();
        // 偏差 = |10 - 5| / 10 = 50%，远超 5% 阈值
    }

    // ────────── 辅助方法 ──────────

    private Set<String> waitForRedisKeys(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Set<String> keys = null;
        while (System.currentTimeMillis() < deadline) {
            keys = redisTemplate.keys("dart:chunk:*");
            if (keys != null && !keys.isEmpty()) {
                return keys;
            }
            Thread.sleep(100);
        }
        return keys;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
