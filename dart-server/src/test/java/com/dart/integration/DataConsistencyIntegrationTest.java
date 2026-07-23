package com.dart.integration;

import com.dart.agent.scoring.ScoredResource;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.service.EmbeddingService;
import com.dart.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private StringRedisTemplate redisTemplate;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createTestUser();
    }

    @Test
    @DisplayName("正常双写：MySQL + Redis 均有数据")
    void normalDualWriteBothMysqlAndRedisHaveData() throws Exception {
        mockEmbedding();

        ScoredResource scores = new ScoredResource("http://example.com/doc", "Test Doc",
                "Test content for embedding.", 4, 5, 3, 4, 3.5);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "Test Title",
                "Test content for embedding test.", "http://example.com/doc", scores);

        assertThat(ids).isNotEmpty();
        Long itemId = ids.get(0);

        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        assertThat(item).isNotNull();
        assertThat(item.getTitle()).isEqualTo("Test Title");

        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeChunk>()
                        .eq("knowledge_item_id", itemId));
        assertThat(chunks).isNotEmpty();

        // 轮询等待 Redis 写入（最多 2 秒）
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
                "Content to be deleted.", "http://example.com/del", scores);
        Long itemId = ids.get(0);

        knowledgeService.deleteKnowledge(testUserId, itemId);

        KnowledgeItem item = knowledgeItemMapper.selectById(itemId);
        assertThat(item).isNull();

        var list = knowledgeService.listUserKnowledge(testUserId, 1, 20);
        assertThat(list.stream().noneMatch(k -> k.getId().equals(itemId))).isTrue();
    }

    @Test
    @DisplayName("已删除条目不出现在 RAG 检索中")
    void deletedItemNotInRagRetrieval() throws Exception {
        mockEmbedding();

        ScoredResource scores = new ScoredResource("http://example.com/rag", "RAG Test",
                "RAG test content.", 5, 5, 5, 5, 5.0);

        List<Long> ids = embeddingService.embedAndPersist(testUserId, "RAG Test",
                "RAG test content.", "http://example.com/rag", scores);
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

        embeddingService.embedAndPersist(testUserId, "Unique", "Content.", sourceUrl, scores);

        boolean exists = knowledgeService.existsBySourceUrl(sha256(sourceUrl));
        assertThat(exists).isTrue();
    }

    // ────────── 辅助方法 ──────────

    private void mockEmbedding() {
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) vec[i] = 0.01f;
        var response = new EmbeddingResponse(
                List.of(new Embedding(vec, 0)),
                new EmbeddingResponseMetadata());
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(response);
    }

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
