package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
import com.dart.entity.Message;
import com.dart.mapper.MessageMapper;
import com.dart.service.RagService.RagResult;
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

    private ChatService chatService;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatModel, searchAgent, curatorAgent, ragService,
                embeddingService, messageMapper);
        emitter = spy(new SseEmitter(60_000L));
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

    // === executeSearchAndCurate ===

    @Test
    void executeSearchAndCurate_optimizedQueryUsedInAllStages() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);

        // 前置合并单次调用 → JSON（isTech + 优化词）
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(
                new AssistantMessage("{\"isTech\":true,\"optimized\":\"优化后的技术问题描述\"}"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        verify(chatModel, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(ragService).retrieve(eq("优化后的技术问题描述"), eq(1L));
        verify(searchAgent).execute(eq("优化后的技术问题描述"), anyInt(), any());
        verify(curatorAgent).curate(eq("优化后的技术问题描述"), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_optimizationFails_usesOriginalQuery() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);
        // 前置合并调用抛异常 → 降级 isTech=true + 优化词为原文
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        verify(searchAgent).execute(eq("Spring Boot"), anyInt(), any());
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
        verify(searchAgent, never()).execute(anyString(), anyInt(), any());
        verify(curatorAgent, never()).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_ragHits_returnsRagAnswer() {
        when(ragService.retrieve(eq("Spring Boot"), eq(1L)))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"))));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer().answer()).contains("Spring Boot");
        // RAG 命中短路：不进入 Curator（Search Agent 已并行启动，不做 never 断言）
        verify(curatorAgent, never()).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_ragHits_doesNotWaitForSlowSearch() {
        // RAG 慢响应（500ms）但命中 → 不等 Search Agent 完成直接返回
        when(ragService.retrieve(anyString(), anyLong())).thenAnswer(inv -> {
            Thread.sleep(500);
            return Optional.of(new RagResult("# 慢查询回答", List.of("https://a.com")));
        });
        when(searchAgent.execute(anyString(), anyInt(), any())).thenAnswer(inv -> {
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
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);

        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(searchAgent).execute(anyString(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_ragMiss_triggersFullSearch() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isFalse();
        assertThat(result.curatedReport()).isNotNull();
        verify(ragService).retrieve(eq("Spring Boot"), eq(1L));
        verify(searchAgent).execute(anyString(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_appendsFollowUpSection() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        ScoredResource sr = new ScoredResource("https://a.com", "A", "content ".repeat(50), 5, 4, 3, 4, 4.0);
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("Java", "# Java 技术学习报告\n\nJava是...".repeat(3),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);

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
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of(sr));
        CuratedReport report = new CuratedReport("query", "# Report\n\nContent ".repeat(10),
                List.of("https://a.com"), Instant.now());
        when(curatorAgent.curate(anyString(), anyList(), any())).thenReturn(report);
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
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of());

        ChatService.SearchResult result = chatService.executeSearchAndCurate("obscure query", 1L, 100L, emitter);
        assertThat(result.ragAnswer()).isNull();
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("未找到");
        verify(curatorAgent, never()).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_searchAgentException_callsFallback() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        when(searchAgent.execute(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("network error"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("query", 1L, 100L, emitter);
        assertThat(result.curatedReport()).isNotNull();
        assertThat(result.curatedReport().markdown()).contains("不可用");
    }
}
