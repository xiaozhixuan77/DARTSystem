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
    void embed_returns1536DimVector() {
        float[] expectedEmbedding = new float[1536];
        expectedEmbedding[0] = 0.5f;
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(expectedEmbedding, 0))));

        float[] result = embeddingService.embed("测试文本");
        assertThat(result).hasSize(1536);
        assertThat(result[0]).isEqualTo(0.5f);
        verify(embeddingModel).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedAndPersist_normalFlow_savesToMySQLAndRedis() {
        setupMappersAndRedis();
        float[] emb = new float[1536];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String content = "# Spring Boot 教程\n\n这是关于Spring Boot的详细教程内容。".repeat(30);
        ScoredResource scores = new ScoredResource("https://spring.io", "Spring Tutorial", content, 5, 5, 5, 4, 4.85);

        List<Long> ids = embeddingService.embedAndPersist(1L, "Spring Boot", content,
                "https://spring.io", scores);

        assertThat(ids).isNotEmpty();
        verify(knowledgeItemMapper).insert(any(KnowledgeItem.class));
        verify(knowledgeChunkMapper, atLeastOnce()).insert(any(KnowledgeChunk.class));
    }

    @Test
    void embedAndPersist_shortContent_createsSingleChunk() {
        setupMappersAndRedis();
        float[] emb = new float[1536];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        String shortContent = "这是一段简短的Spring教程内容，包含足够的字符来通过最小长度过滤。";
        ScoredResource scores = new ScoredResource("https://x.com", "X", shortContent, 4, 4, 3, 4, 4.0);

        embeddingService.embedAndPersist(1L, "Short", shortContent, "https://x.com", scores);
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
                embeddingService.embedAndPersist(1L, "Title", longContent, "https://a.com", scores))
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

        List<Long> ids = embeddingService.embedAndPersist(1L, "Title", "", "https://a.com", scores);
        assertThat(ids).isNotEmpty();
        verify(knowledgeItemMapper).insert(any(KnowledgeItem.class));
        verify(embeddingModel, never()).call(any(EmbeddingRequest.class));
    }
}
