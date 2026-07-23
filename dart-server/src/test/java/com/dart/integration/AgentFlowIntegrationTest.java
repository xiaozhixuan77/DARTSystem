package com.dart.integration;

import com.dart.agent.scoring.PageContent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.PageFetcher;
import com.dart.agent.search.SearchResult;
import com.dart.agent.search.SearXNGClient;
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
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
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
    private SearXNGClient searXNGClient;

    @MockBean
    private PageFetcher pageFetcher;

    @AfterEach
    void resetMocks() {
        Mockito.reset(chatModel, embeddingModel, searXNGClient, pageFetcher);
    }

    @Test
    @DisplayName("非技术查询返回 NON_TECH_QUERY 错误")
    void nonTechQueryReturnsError() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("NO")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "今天天气怎么样", 1L, emitter);

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

        // 使用统一向量确保余弦相似度 = 1.0
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) vec[i] = 0.02f;
        var embedResponse = new EmbeddingResponse(
                List.of(new Embedding(vec, 0)),
                new EmbeddingResponseMetadata());
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(embedResponse);

        ScoredResource scores = new ScoredResource("http://example.com/rag", "RAG Entry",
                "RAG content for matching test with sufficient length.", 4, 4, 4, 4, 4.0);
        embeddingService.embedAndPersist(userId, "RAG Entry",
                "RAG content for matching test with sufficient length.",
                "http://example.com/rag", scores);

        // 重置 EmbeddingModel 仍返回相同向量 → 余弦相似度 1.0 ≥ 0.80
        Mockito.reset(embeddingModel);
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(embedResponse);

        // ChatModel: 第一次 isTechQuery→YES, 第二次 generateAnswer
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("基于知识库的 RAG 回答")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "技术问题", userId, emitter);

        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer()).isNotNull();
        assertThat(result.ragAnswer().answer()).contains("RAG 回答");
    }

    @Test
    @DisplayName("RAG 未命中 → 完整搜索→评分→整理流程")
    void fullSearchFlowWhenRagMisses() {
        Long userId = createTestUser();

        // RAG 无匹配数据 → 走搜索流程
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) vec[i] = 0.01f;
        var embedResponse = new EmbeddingResponse(
                List.of(new Embedding(vec, 0)),
                new EmbeddingResponseMetadata());
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(embedResponse);

        // SearXNG 返回 2 条结果
        when(searXNGClient.search(anyString(), anyInt()))
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
                "Spring Boot 配置", userId, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("Spring Boot");
    }

    @Test
    @DisplayName("搜索结果为空时返回 NO_QUALITY_RESULTS 错误")
    void emptySearchResultsReturnsError() {
        Long userId = createTestUser();

        float[] vec = new float[1536];
        var embedResponse = new EmbeddingResponse(
                List.of(new Embedding(vec, 0)),
                new EmbeddingResponseMetadata());
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(embedResponse);

        when(searXNGClient.search(anyString(), anyInt())).thenReturn(List.of());

        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("YES")))));

        SseEmitter emitter = new SseEmitter();
        ChatService.SearchResult result = chatService.executeSearchAndCurate(
                "冷门技术问题", userId, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到高质量资源");
    }
}
