package com.dart.service;

import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.splitter.MarkdownSplitter;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private MarkdownSplitter splitter;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        splitter = new MarkdownSplitter(500, 50, 20);
        embeddingService = new EmbeddingService(embeddingModel, splitter, knowledgeItemMapper,
                knowledgeChunkMapper, redisTemplate);
    }

    private void setupMappersAndRedis() {
        doAnswer(inv -> {
            KnowledgeItem item = inv.getArgument(0);
            item.setId(1L);
            return 1;
        }).when(knowledgeItemMapper).insert(any(KnowledgeItem.class));
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @Test
    void embed_returns1024DimVector() {
        float[] expectedEmbedding = new float[1024];
        expectedEmbedding[0] = 0.5f;
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(expectedEmbedding, 0))));

        float[] result = embeddingService.embed("测试文本");
        assertThat(result).hasSize(1024);
        assertThat(result[0]).isEqualTo(0.5f);
        verify(embeddingModel).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedAndPersist_normalFlow_savesToMySQLAndRedis() {
        setupMappersAndRedis();
        float[] emb = new float[1024];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String content = "# Spring Boot 教程\n\n这是关于Spring Boot的详细教程内容。".repeat(30);
        ScoredResource scores = new ScoredResource("https://spring.io", "Spring Tutorial", content, 5, 5, 5, 4, 4.85);

        List<Long> ids = embeddingService.embedAndPersist(1L, "Spring Boot", content,
                "https://spring.io", scores, "操作指南");

        assertThat(ids).isNotEmpty();
        verify(knowledgeItemMapper).insert(any(KnowledgeItem.class));
        verify(knowledgeChunkMapper, atLeastOnce()).insert(any(KnowledgeChunk.class));
    }

    @Test
    void embedAndPersist_shortContent_createsSingleChunk() {
        setupMappersAndRedis();
        float[] emb = new float[1024];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String shortContent = "这是一段简短的Spring教程内容，包含足够的字符来通过最小长度过滤。";
        ScoredResource scores = new ScoredResource("https://x.com", "X", shortContent, 4, 4, 3, 4, 4.0);

        embeddingService.embedAndPersist(1L, "Short", shortContent, "https://x.com", scores, "操作指南");
        verify(knowledgeChunkMapper, atMost(1)).insert(any(KnowledgeChunk.class));
    }

    @Test
    void embedAndPersist_embeddingApiException_throwsError() {
        setupMappersAndRedis();
        String longContent = "Spring Boot 是一个用于构建Java应用程序的框架。".repeat(20);
        ScoredResource scores = new ScoredResource("https://a.com", "A", longContent, 5, 5, 5, 5, 5.0);

        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("API error"));

        assertThatThrownBy(() ->
                embeddingService.embedAndPersist(1L, "Title", longContent, "https://a.com", scores, "操作指南"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void embedAndPersist_emptyContent_stillPersistsItem() {
        doAnswer(inv -> {
            KnowledgeItem item = inv.getArgument(0);
            item.setId(1L);
            return 1;
        }).when(knowledgeItemMapper).insert(any(KnowledgeItem.class));

        ScoredResource scores = new ScoredResource("https://a.com", "A", "", 3, 3, 3, 3, 3.0);

        List<Long> ids = embeddingService.embedAndPersist(1L, "Title", "", "https://a.com", scores, "操作指南");
        assertThat(ids).isNotEmpty();
        verify(knowledgeItemMapper).insert(any(KnowledgeItem.class));
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedAndPersist_persistsTags() {
        setupMappersAndRedis();
        float[] emb = new float[1024];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String content = "# 标题\n\n这是一段用于分块测试的内容。".repeat(30);
        ScoredResource scores = new ScoredResource("https://a.com", "A", content, 5, 5, 5, 5, 5.0);

        embeddingService.embedAndPersist(1L, "标题", content, "https://a.com", scores, "深入原理");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(knowledgeItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getTags()).isEqualTo("深入原理");
    }

    // === rebuildChunksForItem（正文编辑重建）===

    private static KnowledgeChunk oldChunk(Long itemId, int index) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setId((long) index + 1);
        c.setKnowledgeItemId(itemId);
        c.setChunkIndex(index);
        c.setChunkText("旧分块内容");
        c.setEmbeddingId("chunk:" + itemId + ":" + index);
        c.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(new float[1024])));
        return c;
    }

    @Test
    void rebuildChunksForItem_deletesOldChunksInsertsNewAndWritesRedis() {
        when(knowledgeChunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of(oldChunk(1L, 0), oldChunk(1L, 1)));
        float[] emb = new float[1024];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String content = "这是正文编辑后用于重建分块的测试内容内容。".repeat(30);
        embeddingService.rebuildChunksForItem(1L, content);

        verify(knowledgeChunkMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
        verify(knowledgeChunkMapper, atLeastOnce()).insert(any(KnowledgeChunk.class));
        verify(embeddingModel, atLeastOnce()).call(any(EmbeddingRequest.class));
        // 无事务环境 afterCommit 直接执行 → Redis 写入新向量
        verify(redisTemplate).executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
    }

    @Test
    void rebuildChunksForItem_blankContent_onlyDeletesChunksAndRedisKeys() {
        when(knowledgeChunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of(oldChunk(1L, 0)));

        embeddingService.rebuildChunksForItem(1L, "   ");

        verify(knowledgeChunkMapper).delete(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class));
        verify(knowledgeChunkMapper, never()).insert(any(KnowledgeChunk.class));
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
        // 无事务环境 afterCommit 直接执行 → 删除旧 Redis key
        verify(redisTemplate).delete(any(Collection.class));
    }

    @Test
    void rebuildChunksForItem_noOldChunks_stillRebuilds() {
        when(knowledgeChunkMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class)))
                .thenReturn(List.of());
        float[] emb = new float[1024];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String content = "这是正文编辑后用于重建分块的测试内容内容。".repeat(30);
        embeddingService.rebuildChunksForItem(1L, content);

        verify(knowledgeChunkMapper, atLeastOnce()).insert(any(KnowledgeChunk.class));
        verify(redisTemplate).executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
    }
}
