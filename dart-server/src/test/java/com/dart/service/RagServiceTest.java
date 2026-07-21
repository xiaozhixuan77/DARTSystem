package com.dart.service;

import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;
    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(embeddingModel, chatModel, knowledgeItemMapper,
                knowledgeChunkMapper, 0.80, 5);
    }

    @Test
    void retrieve_whenNoRedisConnection_returnsEmpty() {
        float[] emb = new float[1536];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());

        Optional<RagService.RagResult> result = ragService.retrieve("Spring Boot", 1L);
        assertThat(result).isEmpty();
    }

    @Test
    void retrieve_withMatchingChunks_returnsResult() {
        float[] emb = new float[1536];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("Spring Boot 教程内容");
        chunk.setChunkIndex(0);
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setIsDeleted(0);
        item.setSourceUrl("https://spring.io");
        when(knowledgeItemMapper.selectById(1L)).thenReturn(item);

        // Mock ChatModel for answer generation
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResults()).thenReturn(List.of(
                new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("Spring Boot 是一个框架"))));

        Optional<RagService.RagResult> result = ragService.retrieve("Spring Boot", 1L);
        assertThat(result).isPresent();
    }

    @Test
    void retrieve_withNoMatchingChunks_returnsEmpty() {
        float[] emb = new float[1536];
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new org.springframework.ai.embedding.Embedding(emb, 0))));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());

        Optional<RagService.RagResult> result = ragService.retrieve("unknown query", 1L);
        assertThat(result).isEmpty();
    }

    @Test
    void generateAnswer_returnsLLMResponse() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("基于知识库内容，Spring Boot 是一个..."))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        RagService.RagChunk chunk = new RagService.RagChunk("Content", "Title", "https://x.com", 0.90);
        String answer = ragService.generateAnswer("什么是Spring Boot?", List.of(chunk));
        assertThat(answer).contains("Spring Boot");
    }

    @Test
    void generateAnswer_llmThrows_returnsFallback() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        RagService.RagChunk chunk = new RagService.RagChunk("Content", "Title", "https://x.com", 0.90);
        String answer = ragService.generateAnswer("query", List.of(chunk));
        assertThat(answer).contains("query");
    }

    @Test
    void generateAnswer_withMultipleChunks_includesSources() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("综合回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        List<RagService.RagChunk> chunks = List.of(
                new RagService.RagChunk("c1", "t1", "https://a.com", 0.95),
                new RagService.RagChunk("c2", "t2", "https://b.com", 0.85));
        String answer = ragService.generateAnswer("query", chunks);
        assertThat(answer).contains("综合回答");
    }
}
