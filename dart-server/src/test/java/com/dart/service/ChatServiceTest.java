package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.Message;
import com.dart.entity.SearchTrace;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.MessageMapper;
import com.dart.mapper.SearchTraceMapper;
import com.dart.service.RagService.RagResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Mock
    private SearchTraceMapper searchTraceMapper;
    @Mock
    private RelationExtractionService relationExtractionService;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private ConversationSummaryService summaryService;

    private ChatService chatService;
    private SseEmitter emitter;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatModel, searchAgent, curatorAgent, ragService,
                embeddingService, messageMapper, redisTemplate, knowledgeItemMapper,
                searchTraceMapper, relationExtractionService, knowledgeService, summaryService);
        emitter = spy(new SseEmitter(60_000L));
        // lenient：仅缓存相关用例使用，避免其他用例报 UnnecessaryStubbing
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(knowledgeItemMapper.selectCount(any())).thenReturn(0L);
        // 单元测试不走 Spring 容器，手动注入 @Value 配置
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "sedimentEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "sedimentMaxItemsPerUser", 50);
        org.springframework.test.util.ReflectionTestUtils.setField(chatService, "cacheTtlHours", 24L);
        // 沉淀默认走新增路径（防 Mockito 返回 null 导致虚拟线程 NPE）
        lenient().when(knowledgeService.findSimilarItem(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
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
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any(), any());
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout")); // 前置与追问失败降级

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        // 报告无占位节 → 异步沉淀到知识库（标题用 report.title 而非查询原文）
        ArgumentCaptor<ScoredResource> scoresCaptor = ArgumentCaptor.forClass(ScoredResource.class);
        verify(embeddingService, timeout(3000))
                .embedAndPersist(eq(1L), eq("query"), contains("# Report"),
                        eq("https://a.com"), scoresCaptor.capture(), eq("操作指南"));
        // 评分修正：T=4、M=4，A/D 按规则（https://a.com 域名权威 + 报告长度 400+ 字符）
        ScoredResource scores = scoresCaptor.getValue();
        assertThat(scores.getScoreT()).isEqualTo(4);
        assertThat(scores.getScoreM()).isEqualTo(4);
        assertThat(scores.getScoreA()).isGreaterThanOrEqualTo(1);
        assertThat(scores.getScoreD()).isGreaterThanOrEqualTo(2);
        // 中间断言：走到 persist 与 writeCache（finally）
        verify(messageMapper, times(2)).insert(any(Message.class));
        verify(valueOps).set(anyString(), contains("# Report"), any(java.time.Duration.class));
    }

    @Test
    void executeSearchAndCurate_similarItemExists_mergesInsteadOfInsert() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("Spring 三级缓存原理", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));
        KnowledgeItem existing = new KnowledgeItem();
        existing.setId(99L);
        when(knowledgeService.findSimilarItem(eq(1L), eq("Spring 三级缓存原理"), contains("# Report")))
                .thenReturn(Optional.of(existing));

        chatService.executeSearchAndCurate("三级缓存", 1L, 100L, emitter);

        // 相似条目存在 → 合并更新而非新增
        verify(knowledgeService, timeout(3000)).mergeItem(eq(1L), eq(99L),
                eq("Spring 三级缓存原理"), contains("# Report"));
        verify(embeddingService, timeout(3000)).rebuildChunksForItem(eq(99L), contains("# Report"));
        verify(embeddingService, never()).embedAndPersist(any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_sedimentSourceUrl_highestAuthority() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        // 多个来源：docs.spring.io（权威 5）应被选为主要来源而非第一个 blog
        CuratedReport report = new CuratedReport("query",
                "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://blog.example.com/post", "https://docs.spring.io/spring-boot/index.html"),
                Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("三级缓存", 1L, 100L, emitter);

        verify(embeddingService, timeout(3000))
                .embedAndPersist(eq(1L), eq("query"), contains("# Report"),
                        eq("https://docs.spring.io/spring-boot/index.html"), any(), any());
    }

    @Test
    void executeSearchAndCurate_reportSedimented_triggersRelationExtraction() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout")); // 前置与追问失败降级
        when(embeddingService.embedAndPersist(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(99L));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 沉淀成功后触发关系抽取（同一虚拟线程，itemId 取自 embedAndPersist 返回）
        verify(relationExtractionService, timeout(3000))
                .extractAndPersist(eq(1L), eq(99L), contains("# Report"));
        // 随后触发跨条目向量关联（图谱闭环增量通道）
        verify(knowledgeService, timeout(3000)).linkRelatedItems(eq(1L), eq(99L));
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 含占位节的残缺报告不沉淀
        verify(embeddingService, after(1500).never()).embedAndPersist(any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_knowledgeBaseFull_notSedimented() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));
        when(knowledgeItemMapper.selectCount(any())).thenReturn(50L); // 达到上限

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        verify(embeddingService, after(1500).never()).embedAndPersist(any(), any(), any(), any(), any(), any());
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

    @Test
    void preProcessQuery_architectureQuery_promptDemandsFrameworkAnchors() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"LangGraph AutoGen CrewAI Agent 架构对比\","
                        + "\"variants\":[],\"intent\":\"comparison\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("现在主流的Agent系统技术架构", "");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 架构/选型类查询：检索词必须附带主流系统锚点，否则搜索只能返回入门科普
        assertThat(promptText).contains("主流系统")
                .contains("LangGraph").contains("AutoGen").contains("CrewAI");
    }

    @Test
    void preProcessQuery_abstractTechTerms_mustAttachConcreteAnchors() {
        // 云原生/微服务/技术栈 等抽象词必须带具体技术名锚点（否则 Bing 返回云游戏等无关结果）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Kubernetes Docker 云原生\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("云原生与微服务开发", "");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        assertThat(promptText).contains("云原生").contains("Kubernetes").contains("抽象概念词");
    }

    // === 会话记忆注入与续答意图判定 ===

    @Test
    void preProcessQuery_withSummary_injectsMemorySectionIntoPrompt() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring 三级缓存原理\","
                        + "\"variants\":[],\"intent\":\"deep_dive\",\"isFollowUp\":false}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("接着讲讲它", "主题：Spring IoC\n已讨论：三级缓存");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 会话摘要以 <session_memory> 块注入前置调用 prompt（闭合标签只在注入时出现）
        assertThat(promptText).contains("</session_memory>");
        assertThat(promptText).contains("主题：Spring IoC\n已讨论：三级缓存");
    }

    @Test
    void preProcessQuery_withoutSummary_omitsMemorySection() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring 三级缓存原理\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("Spring 三级缓存", "");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 无摘要时不注入 memory 段（省 token）；闭合标签只在注入时出现
        assertThat(promptText).doesNotContain("</session_memory>");
    }

    @Test
    void preProcessQuery_parsesIsFollowUpTrue() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"\","
                        + "\"variants\":[],\"intent\":\"followup\",\"isFollowUp\":true}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("为什么不行", "主题：Spring IoC\n关键结论：三级缓存");
        assertThat(pre.followUp()).isTrue();
        assertThat(pre.intent()).isEqualTo("followup");
    }

    @Test
    void preProcessQuery_missingIsFollowUp_defaultsFalse() {
        // 旧格式 JSON（无 isFollowUp 字段）→ 兼容，默认非续答
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring 三级缓存原理\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("Spring 三级缓存", "主题：Spring IoC");
        assertThat(pre.followUp()).isFalse();
    }

    @Test
    void preProcessQuery_malformedIsFollowUp_defaultsFalse() {
        // isFollowUp 字段畸形（非法值）→ 降级 false，不抛异常
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring 三级缓存\","
                        + "\"variants\":[],\"intent\":\"deep_dive\",\"isFollowUp\":maybe}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryPreProcess pre = chatService.preProcessQuery("Spring 三级缓存", "");
        assertThat(pre.followUp()).isFalse();
    }

    @Test
    void preProcessQuery_promptIncludesFollowUpDetectionInstructions() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring 三级缓存原理\","
                        + "\"variants\":[],\"intent\":\"deep_dive\",\"isFollowUp\":false}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("Spring 三级缓存", "");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 续答意图判定指令 + 无历史时恒 false 的安全约束
        assertThat(promptText).contains("续答");
        assertThat(promptText).contains("isFollowUp");
        assertThat(promptText).contains("无历史");
    }

    @Test
    void preProcessQuery_promptRequiresConciseSearchKeywords() {
        // 长问句 query 必须被优化为简洁搜索词（Bing 对长 query 分词崩坏，实测搜出"当前"词典/时钟结果）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"AI Agent 知识库 架构 选型\","
                        + "\"variants\":[],\"intent\":\"deep_dive\",\"isFollowUp\":false}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.preProcessQuery("当前构建AI Agent知识库的主流技术架构有哪些？包括RAG流程、向量数据库、Embedding模型等技术选型", "");

        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 必须输出"简洁搜索关键词组合"而非"技术问题描述"（长问句句式会击穿 Bing 中文分词）
        assertThat(promptText).contains("搜索关键词");
        assertThat(promptText).contains("删除问句");
        assertThat(promptText).contains("口语化");
        // 移除"输入已清晰准确时只做小幅润色"豁免——该豁免是长问句原样输出的元凶
        assertThat(promptText).doesNotContain("小幅润色");
    }

    // === executeSearchAndCurate ===

    // === 续答路径（模糊输入 → 会话记忆回答，不搜索）===

    @Test
    void executeSearchAndCurate_followUpIntent_answersFromMemory() {
        when(valueOps.get(anyString())).thenReturn(null); // 缓存未命中
        when(summaryService.getSummary(1L, 100L)).thenReturn("主题：Spring IoC\n关键结论：三级缓存解决循环依赖");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            org.springframework.ai.chat.prompt.Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            if (text.startsWith("你是一个技术领域判断")) {
                // 前置调用：判定续答意图
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"isTech\":true,\"optimized\":\"\",\"variants\":[],\"intent\":\"followup\",\"isFollowUp\":true}"))));
            }
            // 会话记忆回答
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "三级缓存是 Spring 解决单例 Bean 循环依赖的机制：\n1. singletonObjects 存放完整 Bean..."))));
        });

        ChatService.SearchResult result = chatService.executeSearchAndCurate("为什么不行", 1L, 100L, emitter);

        assertThat(result.isFollowUpHit()).isTrue();
        assertThat(result.followUpAnswer()).contains("三级缓存");
        // 续答不触发搜索/RAG/Curator
        verify(searchAgent, never()).execute(anyList(), anyInt(), any());
        verify(ragService, never()).retrieve(anyString(), anyLong());
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any(), any());
        // 回答持久化 type=followup（user + assistant 两次 insert）
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, times(2)).insert(msgCaptor.capture());
        Message assistantMsg = msgCaptor.getAllValues().get(1);
        assertThat(assistantMsg.getRole()).isEqualTo("assistant");
        assertThat(assistantMsg.getMetadata()).contains("\"type\":\"followup\"");
        // 链路追踪标记 followup
        ArgumentCaptor<SearchTrace> traceCaptor = ArgumentCaptor.forClass(SearchTrace.class);
        verify(searchTraceMapper).insert(traceCaptor.capture());
        assertThat(traceCaptor.getValue().getCurationType()).isEqualTo("followup");
        assertThat(traceCaptor.getValue().getIntent()).isEqualTo("followup");
        // 本轮问答也更新会话摘要
        verify(summaryService).updateSummary(eq(1L), eq(100L), eq("为什么不行"), contains("三级缓存"));
    }

    @Test
    void executeSearchAndCurate_followUpLlmFails_fallsBackToSearch() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(summaryService.getSummary(1L, 100L)).thenReturn("主题：Spring IoC");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            org.springframework.ai.chat.prompt.Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            if (text.startsWith("你是一个技术领域判断")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"isTech\":true,\"optimized\":\"\",\"variants\":[],\"intent\":\"followup\",\"isFollowUp\":true}"))));
            }
            throw new RuntimeException("memory answer failure"); // 记忆回答与追问均失败（降级）
        });
        // 回退正常搜索流程
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("为什么不行", 1L, 100L, emitter);

        // fail-open：续答失败回退搜索，报告正常产出
        assertThat(result.isFollowUpHit()).isFalse();
        assertThat(result.curatedReport().markdown()).contains("# Report");
        verify(searchAgent).execute(anyList(), anyInt(), any());
    }

    @Test
    void executeSearchAndCurate_normalQuery_noFollowUpPath() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            org.springframework.ai.chat.prompt.Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            if (text.startsWith("你是一个技术领域判断")) {
                // 独立新查询：isFollowUp=false
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"isTech\":true,\"optimized\":\"Spring 三级缓存原理\",\"variants\":[],\"intent\":\"deep_dive\",\"isFollowUp\":false}"))));
            }
            throw new RuntimeException("timeout");
        });

        chatService.executeSearchAndCurate("Spring 三级缓存", 1L, 100L, emitter);

        // 正常查询走完整搜索流程，不走续答；报告产生后仍更新会话摘要（预期行为）
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(summaryService).updateSummary(eq(1L), eq(100L), any(), any());
    }

    // === 会话摘要更新与追问注入 ===

    @Test
    void executeSearchAndCurate_searchPath_updatesSessionSummaryWithoutFollowUpSection() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout")); // 前置/追问均失败降级

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 搜索路径回答产生后更新会话摘要，输入为原始报告（不含"进一步探索"追问段）
        ArgumentCaptor<String> mdCaptor = ArgumentCaptor.forClass(String.class);
        verify(summaryService, timeout(3000)).updateSummary(eq(1L), eq(100L), eq("Spring Boot"), mdCaptor.capture());
        assertThat(mdCaptor.getValue()).contains("# Report");
        assertThat(mdCaptor.getValue()).doesNotContain("进一步探索");
    }

    @Test
    void executeSearchAndCurate_ragHit_updatesSessionSummary() {
        when(valueOps.get(anyString())).thenReturn(null);
        RagResult ragAnswer = new RagResult("RAG 回答内容", List.of("https://a.com"), 0.9);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.of(ragAnswer));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // RAG 命中也有问答产生 → 更新会话摘要
        verify(summaryService).updateSummary(eq(1L), eq(100L), eq("Spring Boot"), eq("RAG 回答内容"));
    }

    @Test
    void executeSearchAndCurate_ragAnswerInsufficient_fallsBackToSearch() {
        when(valueOps.get(anyString())).thenReturn(null);
        RagResult ragAnswer = new RagResult(RagService.RAG_INSUFFICIENT_MARKER,
                List.of("https://a.com"), 0.61);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.of(ragAnswer));
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // RAG 回答不足 → 降级使用并行搜索结果（不短路）
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.ragAnswer()).isNull();
        verify(searchAgent).execute(anyList(), anyInt(), any());
    }

    @Test
    void executeSearchAndCurate_ragAnswerInsufficient_searchFails_returnsError() {
        when(valueOps.get(anyString())).thenReturn(null);
        RagResult ragAnswer = new RagResult(RagService.RAG_INSUFFICIENT_MARKER,
                List.of("https://a.com"), 0.61);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.of(ragAnswer));
        when(searchAgent.execute(anyList(), anyInt(), any())).thenThrow(new RuntimeException("search down"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("搜索服务暂时不可用");
    }

    @Test
    void executeSearchAndCurate_followUpPromptIncludesSessionSummary() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(summaryService.getSummary(1L, 100L)).thenReturn("主题：Spring IoC\n关键结论：三级缓存");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            org.springframework.ai.chat.prompt.Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            if (text.startsWith("你是一个技术领域判断")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\",\"variants\":[],\"intent\":\"how_to\",\"isFollowUp\":false}"))));
            }
            // 追问生成
            return new ChatResponse(List.of(new Generation(new AssistantMessage("- 追问1\n- 追问2"))));
        });

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 追问 prompt 注入会话摘要（可引用前几轮内容）；调用 2 次 = 前置调用 + 追问
        ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel, timeout(3000).times(2)).call(captor.capture());
        String followUpPrompt = captor.getAllValues().stream()
                .filter(p -> p.getInstructions().getFirst().getText().contains("追问方向"))
                .map(p -> p.getInstructions().getFirst().getText())
                .findFirst().orElseThrow();
        assertThat(followUpPrompt).contains("</session_memory>");
        assertThat(followUpPrompt).contains("主题：Spring IoC");
    }

    @Test
    void executeSearchAndCurate_optimizedQueryUsedInAllStages() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

        // 前置合并单次调用 → JSON（isTech + 优化词）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        // 前置合并单次调用已由 preProcessQuery 专门用例覆盖（此处还有追问调用）
        verify(ragService).retrieve(eq("优化后的技术问题描述"), eq(1L));
        verify(searchAgent).execute(eq(List.of("优化后的技术问题描述")), anyInt(), any());
        verify(curatorAgent).curate(eq("优化后的技术问题描述"), anyList(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_usesOptimizedQueryPlusVariants() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Java 内存模型\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Java 内存模型", 1L, 100L, emitter);

        // deep_dive 扩大搜索量到 12
        verify(searchAgent).execute(anyList(), eq(12), any());
        // 意图透传给 CuratorAgent（报告风格）
        verify(curatorAgent).curate(anyString(), anyList(), any(), eq("deep_dive"), any());
    }

    @Test
    void executeSearchAndCurate_quickFact_reducesSearchResultsTo5() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        verify(searchAgent).execute(anyList(), eq(8), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), eq("how_to"), any());
    }

    // === SearchTrace 链路追踪 ===

    @Test
    void executeSearchAndCurate_ragHit_recordsTraceWithScore() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong()))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"), 0.93)));
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"Spring Boot 详解\","
                        + "\"variants\":[],\"intent\":\"deep_dive\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        ArgumentCaptor<SearchTrace> captor = ArgumentCaptor.forClass(SearchTrace.class);
        verify(searchTraceMapper).insert(captor.capture());
        SearchTrace trace = captor.getValue();
        assertThat(trace.getCurationType()).isEqualTo("rag");
        assertThat(trace.getRagHit()).isEqualTo(1);
        assertThat(trace.getRagScore()).isEqualTo(0.93);
        assertThat(trace.getSearchKeywords()).isEqualTo("Spring Boot 详解");
        assertThat(trace.getIntent()).isEqualTo("deep_dive");
    }

    @Test
    void executeSearchAndCurate_nonTech_recordsErrorTrace() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":false,\"optimized\":\"\","
                        + "\"variants\":[],\"intent\":\"non_tech\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("今天天气怎么样?", 1L, 100L, emitter);

        ArgumentCaptor<SearchTrace> captor = ArgumentCaptor.forClass(SearchTrace.class);
        verify(searchTraceMapper).insert(captor.capture());
        SearchTrace trace = captor.getValue();
        assertThat(trace.getCurationType()).isEqualTo("error");
        assertThat(trace.getFallbackReason()).isEqualTo("non_tech_query");
    }

    @Test
    void executeSearchAndCurate_normalFlow_recordsSearchTrace() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化词\","
                        + "\"variants\":[],\"intent\":\"how_to\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        ArgumentCaptor<SearchTrace> captor = ArgumentCaptor.forClass(SearchTrace.class);
        verify(searchTraceMapper).insert(captor.capture());
        SearchTrace trace = captor.getValue();
        assertThat(trace.getCurationType()).isEqualTo("search");
        assertThat(trace.getTotalResults()).isEqualTo(1);
        assertThat(trace.getIntent()).isEqualTo("how_to");
        assertThat(trace.getProcessingTimeMs()).isNotNull();
        assertThat(trace.getUserId()).isEqualTo(1L);
        assertThat(trace.getConversationId()).isEqualTo(100L);
    }

    @Test
    void executeSearchAndCurate_sectionFailed_marksFallbackReason() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query",
                "# Report\n\n> ⚠️ 本节内容生成失败，请稍后重试或访问参考文献了解详情。",
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化词\","
                        + "\"variants\":[],\"intent\":\"how_to\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        ArgumentCaptor<SearchTrace> captor = ArgumentCaptor.forClass(SearchTrace.class);
        verify(searchTraceMapper).insert(captor.capture());
        SearchTrace trace = captor.getValue();
        assertThat(trace.getCurationType()).isEqualTo("search");
        assertThat(trace.getFallbackReason()).isEqualTo("section_failed");
    }

    @Test
    void executeSearchAndCurate_cacheHit_doesNotRecordTrace() throws Exception {
        String cachedJson = OBJECT_MAPPER.writeValueAsString(
                java.util.Map.of(
                        "markdown", "# 缓存报告\n\n完整内容 ".repeat(10),
                        "sources", List.of("https://a.com")));
        when(valueOps.get(anyString())).thenReturn(cachedJson);

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        verify(searchTraceMapper, never()).insert(any(SearchTrace.class));
    }

    @Test
    void executeSearchAndCurate_traceInsertFailure_doesNotBreakMainFlow() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(searchTraceMapper.insert(any(SearchTrace.class))).thenThrow(new RuntimeException("db error"));
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化词\","
                        + "\"variants\":[],\"intent\":\"how_to\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // 追踪记录失败仅告警，不影响主流程
        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport().markdown()).contains("Report");
    }

    @Test
    void executeSearchAndCurate_optimizationFails_usesOriginalQuery() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_ragHits_returnsRagAnswer() {
        when(ragService.retrieve(eq("Spring Boot"), eq(1L)))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"))));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer().answer()).contains("Spring Boot");
        // RAG 命中短路：不进入 Curator（Search Agent 已并行启动，不做 never 断言）
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any(), any());
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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_ragMiss_triggersFullSearch() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(ragService).retrieve(eq("Spring Boot"), eq(1L));
        verify(searchAgent).execute(anyList(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any(), any(), any());
    }

    @Test
    void executeSearchAndCurate_appendsFollowUpSection() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("Java", "# Java 技术学习报告\n\nJava是...".repeat(3),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);

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
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
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
        // 空结果提示应引导用户补充具体技术名词重试
        assertThat(result.curatedReport().markdown()).contains("具体技术名词");
        verify(curatorAgent, never()).curate(anyString(), anyList(), any(), any(), any());
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

    // === 变体词主题校验（embedding 相似度）===

    private void stubFullFlowForVariantTest(String variantsJson) {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(variantsJson))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse)
                .thenThrow(new RuntimeException("timeout"));
    }

    @Test
    void executeSearchAndCurate_filtersIrrelevantVariants() {
        stubFullFlowForVariantTest(
                "{\"isTech\":true,\"optimized\":\"Spring 框架\",\"variants\":[\"Spring Boot 教程\",\"腰椎间盘突出治疗\"],\"intent\":\"how_to\"}");
        // 主词与变体词 A 同向量（余弦 1.0 → 保留），变体词 B 反向（余弦 -1.0 → 丢弃）
        when(embeddingService.embed(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            float[] vec = new float[1024];
            java.util.Arrays.fill(vec, q.contains("腰椎") ? -0.5f : 0.5f);
            return vec;
        });

        chatService.executeSearchAndCurate("Spring", 1L, 100L, emitter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchAgent).execute(captor.capture(), anyInt(), any());
        List<String> queries = captor.getValue();
        assertThat(queries).contains("Spring 框架", "Spring Boot 教程");
        assertThat(queries).doesNotContain("腰椎间盘突出治疗");
    }

    @Test
    void executeSearchAndCurate_embeddingFailure_keepsAllVariants() {
        // fail-open：embed 异常时变体词全保留，搜索不降级
        stubFullFlowForVariantTest(
                "{\"isTech\":true,\"optimized\":\"Spring 框架\",\"variants\":[\"Spring Boot 教程\",\"腰椎间盘突出治疗\"],\"intent\":\"how_to\"}");
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("embed api error"));

        chatService.executeSearchAndCurate("Spring", 1L, 100L, emitter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchAgent).execute(captor.capture(), anyInt(), any());
        List<String> queries = captor.getValue();
        assertThat(queries).contains("Spring 框架", "Spring Boot 教程", "腰椎间盘突出治疗");
    }

    // === 沉淀自动打标（intent → 受控类别标签）===

    @ParameterizedTest
    @ValueSource(strings = {"quick_fact", "how_to", "deep_dive", "comparison", "troubleshooting"})
    void executeSearchAndCurate_intentMapsToSedimentTag(String intent) {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(
                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\",\"variants\":[\"sb\"],\"intent\":\"" + intent + "\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse)
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        String expectedTag = switch (intent) {
            case "quick_fact" -> "速查";
            case "deep_dive" -> "深入原理";
            case "comparison" -> "对比分析";
            case "troubleshooting" -> "排错诊断";
            default -> "操作指南";
        };
        verify(embeddingService, timeout(3000))
                .embedAndPersist(eq(1L), any(), any(), any(), any(), eq(expectedTag));
    }

    @Test
    void executeSearchAndCurate_unknownIntent_usesDefaultTag() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyList(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\n完整报告内容 ".repeat(20),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any(), any(), any())).thenReturn(report);
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(
                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\",\"variants\":[\"sb\"],\"intent\":\"general\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse)
                .thenThrow(new RuntimeException("timeout"));

        chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        // LLM 幻觉出未定义意图 → 默认操作指南
        verify(embeddingService, timeout(3000))
                .embedAndPersist(eq(1L), any(), any(), any(), any(), eq("操作指南"));
    }
}
