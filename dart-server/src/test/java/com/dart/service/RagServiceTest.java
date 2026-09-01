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
    @Mock
    private EntityService entityService;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(embeddingService, chatModel, knowledgeItemMapper,
                knowledgeChunkMapper, relationMapper, entityService, 0.80, 5);
        // 实体通道默认无命中（lenient：纯 generateAnswer 用例不触发 retrieve）
        lenient().when(entityService.findItemsByEntityName(any(), any())).thenReturn(java.util.Set.of());
        lenient().when(entityService.matchEntitiesByVector(any(), any())).thenReturn(java.util.Set.of());
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
    void retrieve_entityNameHit_zeroSimilarityChunk_stillExcluded() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Redis 缓存淘汰策略");
        item.setSourceUrl("https://redis.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        // chunk 向量全 0（与查询全 1 余弦 = 0：仅实体名匹配也达不到实体通道下限 0.45）
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("缓存淘汰策略内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(new float[1024])));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        // 实体名"Redis"命中条目 1，但 chunk 相关性不足 → 不注入，走搜索流程
        when(entityService.findItemsByEntityName(1L, "Redis 缓存淘汰策略"))
                .thenReturn(java.util.Set.of(1L));
        when(entityService.matchEntitiesByVector(eq(1L), any())).thenReturn(java.util.Set.of());

        Optional<RagService.RagResult> result = ragService.retrieve("Redis 缓存淘汰策略", 1L);

        assertThat(result).isEmpty();
    }

    @Test
    void retrieve_entityHit_moderateSimilarity_keptButNotStrong() {
        // 查询向量 [1,0,…]，chunk 向量 [0.5, √0.75, 0…]：余弦恰 0.5（≥0.45 实体通道下限，<0.80 正常阈值）
        float[] emb = new float[1024];
        emb[0] = 1.0f;
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Redis 缓存淘汰策略");
        item.setSourceUrl("https://redis.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("缓存淘汰策略内容");
        chunk.setChunkIndex(0);
        float[] chunkVec = new float[1024];
        chunkVec[0] = 0.5f;
        chunkVec[1] = (float) Math.sqrt(0.75);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(chunkVec)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        when(entityService.findItemsByEntityName(1L, "Redis 缓存淘汰策略"))
                .thenReturn(java.util.Set.of(1L));
        when(entityService.matchEntitiesByVector(eq(1L), any())).thenReturn(java.util.Set.of());
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("基于知识库的回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        Optional<RagService.RagResult> result = ragService.retrieve("Redis 缓存淘汰策略", 1L);

        // 实体通道放行（≥0.45）但未达正常阈值（0.80）→ 不算强实体命中：
        // LLM 被指示输出"材料不足"标记 → ChatService 检测标记后降级搜索（不短路）
        assertThat(result).isPresent();
        assertThat(result.get().answer()).isEqualTo("基于知识库的回答");
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText())
                .contains(RagService.RAG_INSUFFICIENT_MARKER);
    }

    @Test
    void retrieve_entityHit_strongSimilarity_skipsInsufficiencyInstruction() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Redis 缓存淘汰策略");
        item.setSourceUrl("https://redis.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("缓存淘汰策略内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(emb)));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        when(entityService.findItemsByEntityName(1L, "Redis 缓存淘汰策略"))
                .thenReturn(java.util.Set.of(1L));
        when(entityService.matchEntitiesByVector(eq(1L), any())).thenReturn(java.util.Set.of());
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("基于知识库的回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        Optional<RagService.RagResult> result = ragService.retrieve("Redis 缓存淘汰策略", 1L);

        // 实体命中且 chunk 达到正常阈值 → 强实体命中，不注入"材料不足"标记（回归锁定）
        assertThat(result).isPresent();
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText())
                .doesNotContain(RagService.RAG_INSUFFICIENT_MARKER);
    }

    @Test
    void retrieve_noEntityHit_lowSimilarityChunkExcluded() {
        float[] emb = new float[1024];
        java.util.Arrays.fill(emb, 1.0f);
        when(embeddingService.embed(anyString())).thenReturn(emb);

        KnowledgeItem item = new KnowledgeItem();
        item.setId(1L);
        item.setUserId(1L);
        item.setTitle("Redis 缓存淘汰策略");
        item.setSourceUrl("https://redis.io");
        item.setIsDeleted(0);
        when(knowledgeItemMapper.selectList(any())).thenReturn(List.of(item));
        when(knowledgeItemMapper.selectBatchIds(anyCollection())).thenReturn(List.of(item));

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeItemId(1L);
        chunk.setChunkText("缓存淘汰策略内容");
        chunk.setChunkIndex(0);
        chunk.setEmbedding(java.util.Base64.getEncoder().encodeToString(
                com.dart.util.VectorUtils.floatArrayToBytes(new float[1024])));
        when(knowledgeChunkMapper.selectList(any())).thenReturn(List.of(chunk));

        // 无实体命中 → 低相似度 chunk 按原阈值过滤
        when(entityService.findItemsByEntityName(any(), anyString())).thenReturn(java.util.Set.of());
        when(entityService.matchEntitiesByVector(any(), any())).thenReturn(java.util.Set.of());

        Optional<RagService.RagResult> result = ragService.retrieve("Redis 缓存淘汰策略", 1L);

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
        String answer = ragService.generateAnswer("什么是Spring Boot?", List.of(chunk), List.of(), false);
        assertThat(answer).contains("Spring Boot");
    }

    @Test
    void generateAnswer_llmThrows_returnsFallback() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        RagService.RagChunk chunk = new RagService.RagChunk("Content", "Title", "https://x.com", 0.90);
        String answer = ragService.generateAnswer("query", List.of(chunk), List.of(), false);
        assertThat(answer).contains("query");
    }

    @Test
    void generateAnswer_entityHit_skipsInsufficiencyInstruction() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("综合回答"))));
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);

        // 实体命中（强信号）+ 低相似度 chunk（0.71 < 强相关阈值）→ 不注入材料不足指令
        RagService.RagChunk chunk = new RagService.RagChunk("Content", "Title", "https://x.com", 0.71);
        ragService.generateAnswer("query", List.of(chunk), List.of(), true);

        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText())
                .doesNotContain(RagService.RAG_INSUFFICIENT_MARKER);
    }

    @Test
    void generateAnswer_withMultipleChunks_includesSources() {
        when(chatResponse.getResults()).thenReturn(List.of(
                new Generation(new AssistantMessage("综合回答"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        List<RagService.RagChunk> chunks = List.of(
                new RagService.RagChunk("c1", "t1", "https://a.com", 0.95),
                new RagService.RagChunk("c2", "t2", "https://b.com", 0.85));
        String answer = ragService.generateAnswer("query", chunks, List.of(), false);
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
        ragService.generateAnswer("query", List.of(chunk), List.of(), false);

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
        ragService.generateAnswer("query", List.of(chunk), List.of(), false);

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
                List.of("Spring Framework 原理 | Spring Framework 是 Spring Boot 的基础..."), false);

        assertThat(answer).contains("综合回答");
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getInstructions().getFirst().getText()).contains("<related>");
    }
}
