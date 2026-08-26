package com.dart.service;

import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.KnowledgeRelation;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.KnowledgeRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;
    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private KnowledgeRelationMapper relationMapper;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(embeddingService, chatModel, knowledgeItemMapper,
                knowledgeChunkMapper, relationMapper, 0.80, 5);
    }

    @Test
    void retrieve_withNoChunks_returnsEmpty() {
        float[] emb = new float[1024];
        lenient().when(embeddingService.embed(anyString())).thenReturn(emb);
        // 用户有知识条目但无 chunk
        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        lenient().when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of());

        Optional<RagService.RagResult> result = ragService.retrieve("Spring Boot", 1L);
        assertThat(result).isEmpty();
    }

    @Test
    void retrieve_withMatchingChunks_returnsResult() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setSourceUrl("https://spring.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("Spring Boot 教程内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(emb)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("Spring Boot 是一个框架"))));

        Optional<RagService.RagResult> result = ragService.retrieve("Spring Boot", 1L);
        assertThat(result).isPresent();

        // 批量加载 KnowledgeItem，杜绝逐条 selectById 的 N+1
        verify(knowledgeItemMapper).selectBatchIds(anyCollection());
        verify(knowledgeItemMapper, never()).selectById(anyLong());
    }

    @Test
    void retrieve_withNoMatchingChunks_returnsEmpty() {
        float[] emb = new float[1024];
        lenient().when(embeddingService.embed(anyString())).thenReturn(emb);
        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        lenient().when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
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

    @Test
    void retrieve_chunkQueryWithoutSqlLimit_loadsAllChunks() {
        // 回归防护：SQL 层不得无排序 LIMIT 截断（否则后沉淀条目的相似块永远轮不到比较）
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        lenient().when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setSourceUrl("https://spring.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("Spring Boot 教程内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(emb)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        ragService.retrieve("Spring Boot", 1L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(knowledgeChunkMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("LIMIT");
    }

    @Test
    void retrieve_matchedMoreThanTopK_keepsHighestOnly() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        lenient().when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setSourceUrl("https://spring.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        java.util.List<KnowledgeChunk> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            KnowledgeChunk c = new KnowledgeChunk();
            c.setKnowledgeItemId(1L);
            c.setChunkText("chunk-" + i);
            c.setChunkIndex(i);
            c.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                    com.dart.util.VectorUtils.floatArrayToBytes(emb)));
            six.add(c);
        }
        when(knowledgeChunkMapper.selectList(any())).thenReturn(six);

        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ragService.retrieve("Spring Boot", 1L);

        // 6 个命中块但 topK=5 → 生成 prompt 只含 5 个 <source>
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String prompt = captor.getValue().getInstructions().getFirst().getText();
        assertThat(prompt).contains("<source>").doesNotContain("chunk-5");
    }

    @Test
    void generateAnswer_weakSimilarity_injectsInsufficientMarkerInstruction() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        // 最高相似度 0.61（弱相关擦线命中）→ prompt 注入"仅输出 RAG_INSUFFICIENT"指令
        RagService.RagChunk chunk = new RagService.RagChunk("内容", "标题", "https://x.com", 0.61);
        ragService.generateAnswer("query", List.of(chunk));

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText())
                .contains(RagService.RAG_INSUFFICIENT_MARKER);
    }

    @Test
    void generateAnswer_strongSimilarity_noMarkerInstruction() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        // 最高相似度 0.9（强相关）→ 正常 prompt，不含标记指令
        RagService.RagChunk chunk = new RagService.RagChunk("内容", "标题", "https://x.com", 0.9);
        ragService.generateAnswer("query", List.of(chunk));

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText())
                .doesNotContain(RagService.RAG_INSUFFICIENT_MARKER);
    }

    // === 1-hop 知识图谱增强 ===

    @Test
    void retrieve_withRelations_appendsRelatedContextToPrompt() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setSourceUrl("https://spring.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));

        KnowledgeItem related = new KnowledgeItem();
        related.setId(2L);
        related.setUserId(1L);
        related.setTitle("Spring Framework 原理");
        related.setContent("Spring Framework 是 Spring Boot 的基础，提供 IoC 容器与 AOP 能力。");
        related.setIsDeleted(0);
        // 两次批量调用：命中条目 [1] 与 1-hop 关联条目 [2]，按参数分发
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            if (ids.contains(2L)) {
                return List.of(related);
            }
            return List.of(item);
        });

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("Spring Boot 教程内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(emb)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        KnowledgeRelation relation = new KnowledgeRelation();
        relation.setSubjectId(1L);
        relation.setPredicate("extends");
        relation.setObjectId(2L);
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));

        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("Spring Boot 是一个框架"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        Optional<RagService.RagResult> result = ragService.retrieve("Spring Boot", 1L);
        assertThat(result).isPresent();

        // 1-hop 关联内容以 <related> 块拼进生成 prompt
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String prompt = captor.getValue().getInstructions().getFirst().getText();
        assertThat(prompt).contains("<related>");
        assertThat(prompt).contains("Spring Framework 原理");
    }

    @Test
    void retrieve_withoutRelations_noRelatedBlockInPrompt() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Spring Boot Guide");
        item.setSourceUrl("https://spring.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("Spring Boot 教程内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(emb)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        // 无关系 → 不拼接 related 块
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("Spring Boot 是一个框架"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ragService.retrieve("Spring Boot", 1L);

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText()).doesNotContain("<related>");
    }

    @Test
    void generateAnswer_withRelatedContext_appendsRelatedBlock() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("综合回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        RagService.RagChunk chunk = new RagService.RagChunk("c1", "t1", "https://a.com", 0.95);
        String answer = ragService.generateAnswer("query", List.of(chunk),
                List.of("Spring Framework 原理 | Spring Framework 是 Spring Boot 的基础..."));

        assertThat(answer).contains("综合回答");
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText()).contains("<related>");
    }
}
