package com.dart.integration;

import com.dart.broadcast.SseSink;
import com.dart.agent.scoring.PageContent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.BingSearchClient;
import com.dart.agent.search.PageFetcher;
import com.dart.agent.search.SearchResult;
import com.dart.entity.Conversation;
import com.dart.mapper.ConversationMapper;
import com.dart.service.ChatService;
import com.dart.service.EmbeddingService;
import com.dart.service.EntityExtractionService;
import com.dart.service.EntityService;
import com.dart.service.QueryPreProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.Mockito.mock;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AgentFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ChatService chatService;

    @Autowired
    private QueryPreProcessor queryPreProcessor;

    @Autowired
    private EmbeddingService embeddingService;

    @MockBean
    private BingSearchClient bingSearchClient;

    @MockBean
    private PageFetcher pageFetcher;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private com.dart.mapper.KnowledgeEntityMapper entityMapper;

    @Autowired
    private com.dart.mapper.KnowledgeItemEntityMapper itemEntityMapper;

    @Autowired
    private com.dart.mapper.KnowledgeItemMapper knowledgeItemMapper;

    @Autowired
    private EntityService entityService;

    @AfterEach
    void resetMocks() {
        Mockito.reset(chatModel, embeddingModel, bingSearchClient, pageFetcher);
    }

    @Test
    @DisplayName("实体 upsert 同名聚合、关联幂等、列表计数（真实 DB）")
    void entityUpsertLinkAndListOnRealDb() {
        Long userId = createTestUser();

        EntityExtractionService.ExtractedEntities extracted =
                new EntityExtractionService.ExtractedEntities(List.of("Spring"), List.of("Redis", "LRU"));
        List<Long> ids1 = entityService.upsertEntities(userId, extracted);
        // 同名再次 upsert → 复用不新增
        List<Long> ids2 = entityService.upsertEntities(userId, extracted);
        assertThat(ids2).containsExactlyElementsOf(ids1);
        assertThat(entityMapper.selectCount(null)).isEqualTo(3);

        Long itemId = createKnowledgeItem(userId, "Redis 缓存淘汰策略");
        entityService.linkItems(userId, itemId, ids1);
        entityService.linkItems(userId, itemId, ids1); // 幂等
        assertThat(itemEntityMapper.selectCount(null)).isEqualTo(3);

        // 列表计数：Redis 关联 1 条
        var vos = entityService.listEntities(userId, null);
        assertThat(vos).anyMatch(v -> v.name().equals("Redis") && v.itemCount() == 1);
        // 名称匹配检索
        assertThat(entityService.findItemsByEntityName(userId, "redis 缓存策略")).contains(itemId);
    }

    private Long createKnowledgeItem(Long userId, String title) {
        com.dart.entity.KnowledgeItem item = new com.dart.entity.KnowledgeItem();
        item.setUserId(userId);
        item.setTitle(title);
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);
        return item.getId();
    }

    @Test
    @DisplayName("首条消息将默认标题会话更新为消息内容")
    void firstMessage_updatesDefaultTitleConversation() {
        Long userId = createTestUser();
        Long convId = insertConversation(userId, "新对话");

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":false,\"optimized\":\"\"}")))));

        chatService.executeSearchAndCurate("Spring Boot 入门", userId, convId, mock(SseSink.class));

        assertThat(conversationMapper.selectById(convId).getTitle()).isEqualTo("Spring Boot 入门");
    }

    @Test
    @DisplayName("首条消息不覆盖用户自定义标题")
    void firstMessage_doesNotOverwriteCustomTitle() {
        Long userId = createTestUser();
        Long convId = insertConversation(userId, "我的自定义会话");

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":false,\"optimized\":\"\"}")))));

        chatService.executeSearchAndCurate("Spring Boot 入门", userId, convId, mock(SseSink.class));

        assertThat(conversationMapper.selectById(convId).getTitle()).isEqualTo("我的自定义会话");
    }

    private Long insertConversation(Long userId, String title) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(title);
        conv.setIsDeleted(0);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv.getId();
    }

    @Test
    @DisplayName("非技术查询返回 NON_TECH_QUERY 错误")
    void nonTechQueryReturnsError() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":false,\"optimized\":\"\"}")))));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "今天天气怎么样", userId, convId, sink);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("仅支持技术");
    }

    @Test
    @DisplayName("LLM 调用异常时前置处理降级为技术查询+原文")
    void preProcessFallsBackToTechAndOriginalWhenLlmFails() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("LLM timeout"));

        ChatService.QueryPreProcess pre = queryPreProcessor.preProcessQuery("Spring Boot 配置问题");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("Spring Boot 配置问题");
    }

    @Test
    @DisplayName("技术查询判定通过")
    void techQueryReturnsTrue() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot configuration\"}")))));

        ChatService.QueryPreProcess pre = queryPreProcessor.preProcessQuery("Spring Boot configuration");
        assertThat(pre.isTech()).isTrue();
        assertThat(pre.optimized()).isEqualTo("Spring Boot configuration");
    }

    @Test
    @DisplayName("RAG 命中时跳过搜索直接返回知识库答案")
    void ragHitSkipsSearchAndReturnsAnswer() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        mockEmbedding(0.02f);

        ScoredResource scores = new ScoredResource("http://example.com/rag", "RAG Entry",
                "RAG content for matching test with sufficient length.", 4, 4, 4, 4, 4.0);
        embeddingService.embedAndPersist(userId, "RAG Entry",
                "RAG content for matching test with sufficient length.",
                "http://example.com/rag", scores, "操作指南");

        // 重置后仍返回相同向量 → 余弦相似度 1.0 ≥ 0.80
        Mockito.reset(embeddingModel);
        mockEmbedding(0.02f);

        // ChatModel: 前置合并(JSON)；Curator 三节走 stream（与搜索路径同构）
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\"}")))));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("# 核心概念内容\n\n基于知识库材料的完整说明。".repeat(30)))))));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "技术问题", userId, convId, sink);

        assertThat(result.isRagHit()).isTrue();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("核心概念内容");
    }

    @Test
    @DisplayName("RAG 未命中 → 完整搜索→评分→整理流程")
    void fullSearchFlowWhenRagMisses() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        // RAG 无匹配数据 → 走搜索流程
        mockEmbedding();

        // Bing 返回 2 条结果
        when(bingSearchClient.search(anyString(), anyInt()))
                .thenReturn(List.of(
                        new SearchResult("http://example.com/a", "Spring Boot Config A", "spring boot configuration guide"),
                        new SearchResult("http://example.com/b", "Spring Boot Config B", "spring boot config tips")));

        // PageFetcher 返回可评分的内容
        when(pageFetcher.fetch(anyString()))
                .thenReturn(new PageContent("http://example.com/a", "Result A",
                        "Spring Boot configuration guide with detailed examples and code snippets.",
                        800, 3, true, false, 2026))
                .thenReturn(new PageContent("http://example.com/b", "Result B",
                        "Advanced Spring tutorial covering dependency injection and AOP.",
                        600, 2, false, true, 2025));

        // ChatModel 调用链: 前置合并(JSON) + 节 1（stream）+ 节 2/3（call）
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\"}")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("4")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "# Spring Boot 配置指南\n\n整理后的报告内容。")))));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "# Spring Boot 核心概念\n\n概念内容。"))))));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, sink);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("Spring Boot");
    }

    @Test
    @DisplayName("Curator LLM 失败时降级返回 fallback 资源列表")
    void curatorLlmFailureReturnsFallbackReport() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        mockEmbedding();

        when(bingSearchClient.search(anyString(), anyInt()))
                .thenReturn(List.of(
                        new SearchResult("http://example.com/a", "Spring Boot Resource A", "spring boot configuration"),
                        new SearchResult("http://example.com/b", "Spring Boot Resource B", "spring boot tutorial")));

        when(pageFetcher.fetch(anyString()))
                .thenReturn(new PageContent("http://example.com/a", "Resource A",
                        "Spring Boot configuration guide with detailed examples and code snippets.",
                        800, 3, true, false, 2026))
                .thenReturn(new PageContent("http://example.com/b", "Resource B",
                        "Advanced Spring Boot tutorial covering dependency injection and configuration in depth.",
                        800, 3, false, true, 2026));

        // ChatModel 调用链: 前置合并(JSON) → 报告节生成失败
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\"}")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenThrow(new RuntimeException("LLM 报告生成超时"));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, sink);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown())
                .contains("AI 报告生成暂时不可用");
        assertThat(result.curatedReport().markdown())
                .contains("Resource A")
                .contains("Resource B");
    }

    @Test
    @DisplayName("Bing 搜索异常时 SearchAgent 内部降级返回空结果")
    void bingExceptionGracefulDegradation() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        mockEmbedding();

        when(bingSearchClient.search(anyString(), anyInt()))
                .thenThrow(new RuntimeException("Bing connection refused"));

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\"}")))));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, sink);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到高质量资源");
    }

    @Test
    @DisplayName("相同查询第二次命中缓存跳过完整流程")
    void cacheHitSkipsFullFlowOnSecondQuery() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        mockEmbedding();

        when(bingSearchClient.search(anyString(), anyInt()))
                .thenReturn(List.of(
                        new SearchResult("http://example.com/a", "Spring Boot Config Guide", "spring boot cache configuration")));
        when(pageFetcher.fetch(anyString()))
                .thenReturn(new PageContent("http://example.com/a", "Result A",
                        "Spring Boot configuration guide with detailed examples and code snippets.",
                        800, 3, true, false, 2026));

        // 前置合并(JSON) + 节 1（stream）+ 节 2/3（call，复用最后一个值）
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"Spring Boot 配置\"}")))))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("# 概念节内容\n\n详细说明。".repeat(200))))));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("# 概念节内容\n\n详细说明。".repeat(200)))))));

        String query = "Spring Boot 缓存配置";
        chatService.executeSearchAndCurate(query, userId, convId, mock(SseSink.class));

        // 第二次相同查询 → 命中缓存，Agent 全部不参与
        Mockito.reset(bingSearchClient, pageFetcher, chatModel);
        ChatService.SearchResult second = chatService.executeSearchAndCurate(
                query, userId, convId, mock(SseSink.class));

        assertThat(second.isRagHit()).isFalse();
        assertThat(second.curatedReport()).isNotNull();
        assertThat(second.curatedReport().markdown()).contains("概念节内容");
    }

    @Test
    @DisplayName("搜索结果为空时返回 NO_QUALITY_RESULTS 错误")
    void emptySearchResultsReturnsError() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        mockEmbedding();

        when(bingSearchClient.search(anyString(), anyInt())).thenReturn(List.of());

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "{\"isTech\":true,\"optimized\":\"冷门技术问题\"}")))));

        SseSink sink = mock(SseSink.class);
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "冷门技术问题", userId, convId, sink);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到高质量资源");
    }
}
