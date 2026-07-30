package com.dart.integration;

import com.dart.agent.scoring.PageContent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.BingSearchClient;
import com.dart.agent.search.PageFetcher;
import com.dart.agent.search.SearchResult;
import com.dart.service.ChatService;
import com.dart.service.EmbeddingService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private EmbeddingService embeddingService;

    @MockBean
    private BingSearchClient bingSearchClient;

    @MockBean
    private PageFetcher pageFetcher;

    @AfterEach
    void resetMocks() {
        Mockito.reset(chatModel, embeddingModel, bingSearchClient, pageFetcher);
    }

    @Test
    @DisplayName("非技术查询返回 NON_TECH_QUERY 错误")
    void nonTechQueryReturnsError() {
        Long userId = createTestUser();
        Long convId = createTestConversation(userId);

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("NO")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "今天天气怎么样", userId, convId, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("仅支持技术");
    }

    @Test
    @DisplayName("LLM 调用异常时 isTechQuery 降级返回 true")
    void isTechQueryFallsBackToTrueWhenLlmFails() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("LLM timeout"));

        boolean isTech = chatService.isTechQuery("Spring Boot 配置问题");
        assertThat(isTech).isTrue();
    }

    @Test
    @DisplayName("技术查询判定通过")
    void techQueryReturnsTrue() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))));

        boolean isTech = chatService.isTechQuery("Spring Boot configuration");
        assertThat(isTech).isTrue();
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
                "http://example.com/rag", scores);

        // 重置后仍返回相同向量 → 余弦相似度 1.0 ≥ 0.80
        Mockito.reset(embeddingModel);
        mockEmbedding(0.02f);

        // ChatModel: 第一次 isTechQuery→YES, 第二次 generateAnswer
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("基于知识库的 RAG 回答")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "技术问题", userId, convId, emitter);

        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer()).isNotNull();
        assertThat(result.ragAnswer().answer()).contains("RAG 回答");
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
                        new SearchResult("http://example.com/a", "Result A", "Snippet A"),
                        new SearchResult("http://example.com/b", "Result B", "Snippet B")));

        // PageFetcher 返回可评分的内容
        when(pageFetcher.fetch(anyString()))
                .thenReturn(new PageContent("http://example.com/a", "Result A",
                        "Spring Boot configuration guide with detailed examples and code snippets.",
                        800, 3, true, false, 2026))
                .thenReturn(new PageContent("http://example.com/b", "Result B",
                        "Advanced Spring tutorial covering dependency injection and AOP.",
                        600, 2, false, true, 2025));

        // ChatModel 调用链: isTechQuery + 2xMatchScorer + CuratorGenerateReport
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("4")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage(
                                "# Spring Boot 配置指南\n\n整理后的报告内容。")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, emitter);

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
                        new SearchResult("http://example.com/a", "Resource A", "Snippet A"),
                        new SearchResult("http://example.com/b", "Resource B", "Snippet B")));

        when(pageFetcher.fetch(anyString()))
                .thenReturn(new PageContent("http://example.com/a", "Resource A",
                        "Spring Boot configuration guide with detailed examples and code snippets.",
                        800, 3, true, false, 2026))
                .thenReturn(new PageContent("http://example.com/b", "Resource B",
                        "Advanced Spring Boot tutorial covering dependency injection and configuration in depth.",
                        800, 3, false, true, 2026));

        // ChatModel 调用链: isTechQuery + 2×MatchScorer → report generation 抛异常
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("5")))))
                .thenThrow(new RuntimeException("LLM 报告生成超时"));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, emitter);

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
                        List.of(new Generation(new AssistantMessage("YES")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "Spring Boot 配置", userId, convId, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到高质量资源");
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
                        List.of(new Generation(new AssistantMessage("YES")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "冷门技术问题", userId, convId, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到高质量资源");
    }
}
