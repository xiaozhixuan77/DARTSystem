package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.Message;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.MessageMapper;
import com.dart.service.RagService.RagResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;
    @Mock
    private SearchAgent searchAgent;
    @Mock
    private CuratorAgent curatorAgent;
    @Mock
    private RagService ragService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private KnowledgeItemMapper knowledgeItemMapper;

    private ChatService chatService;
    private SseEmitter emitter;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatModel, searchAgent, curatorAgent, ragService,
                embeddingService, messageMapper, redisTemplate, knowledgeItemMapper);
        emitter = spy(new SseEmitter(60_000L));
        // lenient：仅缓存相关用例使用，避免其他用例报 UnnecessaryStubbing
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(knowledgeItemMapper.selectCount(any())).thenReturn(0L);
        // 单元测试不走 Spring 容器，手动注入 @Value 配置
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "sedimentEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "sedimentMaxItemsPerUser", 50);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "cacheTtlHours", 24L);
    }

    // === 查询缓存 ===

    @Test
    void executeSearchAndCurate_cacheHit_skipsFullFlow() throws Exception {
        String cachedJson = OBJECT_MAPPER.writeValueAsString(
                java.util.Map.of(
                        "markdown", "# 缓存报告\n\n完整内容 ".repeat(10),
                        "sources", List.of("https://a.com")));
        when(valueOps.get(anyString())).thenReturn(cachedJson);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport().markdown()).contains("缓存报告");
        assertThat(result.curatedReport().sources()).contains("https://a.com");
        verify(ragService, never()).retrieve(anyString(), anyLong());
        verify(searchAgent, never()).execute(anyList(), anyInt(), any());
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any());
        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(messageMapper, times(2)).insert(any(Message.class));
    }

    @Test
    void executeSearchAndCurate_cacheMiss_runsFullFlowAndWritesCache() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout")); // 前置与追问均失败降级

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(valueOps).get(anyString());
        // 报告与追问完成后写入缓存（带 TTL）
        verify(valueOps).set(anyString(), contains("# Report"), any(java.time.Duration.class));
    }

    @Test
    void executeSearchAndCurate_cacheKey_normalizesQuery() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("  Spring Boot  ", 1L, 100L, emitter);
        chatService.executeSearchAndCurate("spring boot", 1L, 100L, emitter);

        // 规范化后（trim + 小写）两次查询使用同一缓存 key
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).get(keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0)).isEqualTo(keyCaptor.getAllValues().get(1));
    }

    // === 报告自动沉淀（预灌 RAG 知识库）===

    @Test
    void executeSearchAndCurate_reportSedimentedToKnowledgeBase() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout")); // 前置与追问失败降级

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        // 报告无占位节 → 异步沉淀到知识库
        verify(embeddingService, timeout(3000))
                .embedAndPersist(eq(1L), eq("Spring Boot"), contains("# Report"),
                        eq("https://a.com"), any());
        // 中间断言：走到 persist 与 writeCache（finally）
        verify(messageMapper, times(2)).insert(any(Message.class));
        verify(valueOps).set(anyString(), contains("# Report"), any(java.time.Duration.class));
    }

    @Test
    void executeSearchAndCurate_reportWithPlaceholder_notSedimented() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query",
                "# Report\n\n> ⚠️ 本节内容生成失败，请稍后重试或访问参考文献了解详情。",
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 含占位节的残缺报告不沉淀
        verify(embeddingService, after(1500).never()).embedAndPersist(any(), any(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_knowledgeBaseFull_notSedimented() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));
        when(knowledgeItemMapper.selectCount(any())).thenReturn(50L); // 达到上限

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        verify(embeddingService, after(1500).never()).embedAndPersist(any(), any(), any(), any(), any());
    }

    // === preProcessQuery（领域判断 + 提示词优化合并为一次调用）===

    @Test
    void preProcessQuery_whenLLMReturnsTechAndOptimized_returnsBoth() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"如何在 Spring Boot 中配置多数据源\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("怎么配多数据源");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("如何在 Spring Boot 中配置多数据源");
        verify(chatModel, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void preProcessQuery_whenLLMReturnsNonTech_returnsIsTechFalse() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":false,\"optimized\":\"\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("今天天气怎么样?");
        assertThat(pre.isTech()).isFalse();
    }

    @Test
    void preProcessQuery_whenLLMThrows_fallsBackToTechAndOriginal() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("query");
    }

    @Test
    void preProcessQuery_blankOptimized_fallsBackToOriginal() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("query");
    }

    @Test
    void preProcessQuery_malformedJson_fallsBackToTechAndOriginal() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("不稳定的输出文本，无法解析"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("query");
    }

    @Test
    void preProcessQuery_whenLLMReturnsVariants_parsesVariantList() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring Boot 多数据源配置\","
                        + "\"variants\":[\"Spring Boot 双数据源 教程\",\"Spring Boot DataSource 原理\"]}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("怎么配多数据源");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.variants()).containsExactly("Spring Boot 双数据源 教程", "Spring Boot DataSource 原理");
    }

    @Test
    void preProcessQuery_missingVariants_returnsEmptyVariants() {
        // 旧格式 JSON（无 variants 字段）→ 兼容，变体为空列表
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.variants()).isEmpty();
    }

    @Test
    void preProcessQuery_malformedVariants_returnsEmptyVariants() {
        // variants 数组字段畸形（未闭合）→ 解析失败降级为空列表，不抛异常
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化词\",\"variants\":[\"未闭合\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("优化词");
        assertThat(pre.variants()).isEmpty();
    }

    @Test
    void preProcessQuery_whenLLMReturnsIntent_parsesIntent() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Java 内存模型详解\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("Java 内存模型");
        assertThat(pre.intent()).isEqualTo("deep_dive");
    }

    @Test
    void preProcessQuery_missingIntent_defaultsToHowTo() {
        // 旧格式 JSON（无 intent 字段）→ 兼容，默认 how_to
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.intent()).isEqualTo("how_to");
    }

    @Test
    void preProcessQuery_malformedIntent_defaultsToHowTo() {
        // intent 字段畸形（未闭合引号）→ 解析失败降级 how_to，不抛异常
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化词\",\"intent\":\"deep_dive}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("query");
        assertThat(pre.intent()).isEqualTo("how_to");
    }

    @Test
    void preProcessQuery_nonTech_normalizesIntentToNonTech() {
        // isTech=false 时意图强制归一化为 non_tech（即使 LLM 输出了技术意图）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":false,\"optimized\":\"\",\"intent\":\"how_to\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("今天天气怎么样?");
        assertThat(pre.isTech()).isFalse();
        assertThat(pre.intent()).isEqualTo("non_tech");
    }

    // === executeSearchAndCurate ===

    @Test
    void executeSearchAndCurate_optimizedQueryUsedInAllStages() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);

        // 前置合并单次调用 → JSON（isTech + 优化词）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        // 前置合并单次调用已由 preProcessQuery 专门用例覆盖（此处还有追问调用）
        verify(ragService).retrieve(eq("优化后的技术问题描述"), eq(1L));
        verify(searchAgent).execute(eq(List.of("优化后的技术问题描述")), anyInt(), any());
        verify(curatorAgent).curate(eq("优化后的技术问题描述"), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_usesOptimizedQueryPlusVariants() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\","
                        + "\"variants\":[\"变体查询1\",\"变体查询2\"]}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // SearchAgent 收到主词 + 2 个变体词
        verify(searchAgent).execute(eq(List.of("优化后的技术问题描述", "变体查询1", "变体查询2")),
                anyInt(), any());
        // RAG 只用主词（变体词仅扩展网络搜索）
        verify(ragService).retrieve(eq("优化后的技术问题描述"), eq(1L));
    }

    // === 意图路由 ===

    @Test
    void executeSearchAndCurate_deepDive_expandsSearchAndPassesIntent() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Java 内存模型\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Java 内存模型", 1L, 100L, emitter);

        // deep_dive 扩大搜索量到 12
        verify(searchAgent).execute(anyList(), eq(12), any());
        // 意图透传给 CuratorAgent（报告风格）
        verify(curatorAgent).curate(anyString(), anyList(), any(), eq("deep_dive"));
    }

    @Test
    void executeSearchAndCurate_quickFact_reducesSearchResultsTo5() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Docker 是什么\","
                        + "\"variants\":[],\"intent\":\"quick_fact\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Docker 是什么", 1L, 100L, emitter);

        // quick_fact 收窄搜索量到 5
        verify(searchAgent).execute(anyList(), eq(5), any());
    }

    @Test
    void executeSearchAndCurate_missingIntent_usesDefaultSearchSizeAndHowTo() {
        // intent 缺失 → 默认 how_to → 8（与升级前行为一致）
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        verify(searchAgent).execute(anyList(), eq(8), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), eq("how_to"));
    }

    @Test
    void executeSearchAndCurate_optimizationFails_usesOriginalQuery() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        // 前置合并调用抛异常 → 降级 isTech=true + 优化词为原文
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        verify(searchAgent).execute(eq(List.of("Spring Boot")), anyInt(), any());
    }

    @Test
    void executeSearchAndCurate_nonTechQuery_returnsErrorReport() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":false,\"optimized\":\"\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("今天天气怎么样", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("技术");
        verify(ragService, never()).retrieve(anyString(), anyLong());
        verify(searchAgent, never()).execute(anyList(), anyInt(), any());
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any());
    }

    @Test
    void executeSearchAndCurate_ragHits_returnsRagAnswer() {
        when(ragService.retrieve(eq("Spring Boot"), eq(1L)))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"))));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer().answer()).contains("Spring Boot");
        // RAG 命中短路：不进入 Curator（Search Agent 已并行启动，不做 never 断言）
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any());
    }

    @Test
    void executeSearchAndCurate_ragHits_doesNotWaitForSlowSearch() {
        // RAG 慢响应（500ms）但命中 → 不等 Search Agent 完成直接返回
        when(ragService.retrieve(anyString(), anyLong())).thenAnswer(inv -> {
            Thread.sleep(500);
            return Optional.of(new RagResult("# 慢查询回答", List.of("https://a.com")));
        });
        when(searchAgent.execute(anyList(), anyInt(), any())).thenAnswer(inv -> {
            Thread.sleep(5000); // 模拟慢搜索：若等待则测试耗时 5s+
            return List.of();
        });

        long start = System.currentTimeMillis();
        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isRagHit()).isTrue();
        assertThat(elapsed).isLessThan(4000);
    }

    @Test
    void executeSearchAndCurate_ragException_continuesWithSearch() {
        when(ragService.retrieve(anyString(), anyLong()))
                .thenThrow(new RuntimeException("RAG 检索失败"));
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), any());
    }

    @Test
    void executeSearchAndCurate_ragMiss_triggersFullSearch() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(ragService).retrieve(eq("Spring Boot"), eq(1L));
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), any());
    }

    @Test
    void executeSearchAndCurate_appendsFollowUpSection() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("Java", "# Java 技术学习报告\n\nJava是...".repeat(3),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);

        // 前置合并第一次调用 → JSON（isTech + 优化词）; generateFollowUp 第二次调用 → 追问
        when(chatResponse.getResults())
                .thenReturn(List.of(new Generation(
                        new AssistantMessage("{\"isTech\":true,\"optimized\":\"JAVA\"}"))))
                .thenReturn(List.of(new Generation(new AssistantMessage(
                        "- 想深入了解Java集合框架吗？\n- 对Spring Boot开发感兴趣？\n- 想了解Java面试常见考点吗？"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("JAVA", 1L, 100L, emitter);
        assertThat(result.curatedReport()).isNotNull();
        // 追问不再拼入返回对象，而是更新已持久化的消息（消息含追问）
        assertThat(result.curatedReport().markdown()).doesNotContain("## 进一步探索");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, atLeast(2)).insert(any(Message.class));
        verify(messageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getContent()).contains("## 进一步探索");
        assertThat(captor.getValue().getContent()).contains("Java集合框架");
    }

    @Test
    void executeSearchAndCurate_followUpFails_stillReturnsReport() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any())).thenReturn(report);
        // 前置合并与追问 LLM 调用均失败
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("JAVA", 1L, 100L, emitter);
        // 即使追问失败，报告仍正常返回
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).doesNotContain("## 进一步探索");
        verify(messageMapper, never()).updateById(any(Message.class));
    }

    @Test
    void executeSearchAndCurate_searchReturnsEmpty_returnsErrorReport() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of());

        ChatService.SearchResult result = chatService.executeSearchAndCurate("obscure query", 1L, 100L, emitter);
        assertThat(result.ragAnswer()).isNull();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到");
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any());
    }

    @Test
    void executeSearchAndCurate_searchAgentException_callsFallback() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        when(searchAgent.execute(anyList(), anyInt(), any()))
                .thenThrow(new RuntimeException("network error"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("query", 1L, 100L, emitter);
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("不可用");
    }
}
