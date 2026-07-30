package com.dart.service;

import com.dart.agent.curator.CuratedReport;
import com.dart.agent.curator.CuratorAgent;
import com.dart.agent.scoring.ScoredResource;
import com.dart.agent.search.SearchAgent;
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

import com.dart.entity.Message;

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

    // === analyzeQuery ===

    @Test
    void analyzeQuery_clearTechQuery_returnsNotVague() {
        String json = "{\"isTech\":true,\"isVague\":false}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryAnalysis result = chatService.analyzeQuery("Spring Boot 如何配置CORS");

        assertThat(result.isTech()).isTrue();
        assertThat(result.isVague()).isFalse();
    }

    @Test
    void analyzeQuery_vagueSingleKeyword_returnsVagueWithQuestions() {
        String json = "{\"isTech\":true,\"isVague\":true,\"detectedTopic\":\"Java\"," +
                "\"clarifyingQuestions\":[\"Java语法基础入门\",\"Java框架选择推荐\",\"Java开发环境搭建\"]}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryAnalysis result = chatService.analyzeQuery("JAVA");

        assertThat(result.isTech()).isTrue();
        assertThat(result.isVague()).isTrue();
        assertThat(result.detectedTopic()).isEqualTo("Java");
        assertThat(result.clarifyingQuestions()).hasSize(3);
        assertThat(result.clarifyingQuestions().get(0)).contains("Java");
    }

    @Test
    void analyzeQuery_nonTech_returnsNotTech() {
        String json = "{\"isTech\":false}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryAnalysis result = chatService.analyzeQuery("今天天气怎么样");

        assertThat(result.isTech()).isFalse();
    }

    @Test
    void analyzeQuery_llmFailure_defaultsToClearTech() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));

        ChatService.QueryAnalysis result = chatService.analyzeQuery("anything");

        assertThat(result.isTech()).isTrue();
        assertThat(result.isVague()).isFalse();
    }

    @Test
    void analyzeQuery_invalidJson_defaultsToClearTech() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage("not json"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.QueryAnalysis result = chatService.analyzeQuery("test");

        assertThat(result.isTech()).isTrue();
        assertThat(result.isVague()).isFalse();
    }

    // === isTechQuery (backward compat) ===

    @Test
    void isTechQuery_whenLLMReturnsYes_returnsTrue() {
        String json = "{\"isTech\":true,\"isVague\":false}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        assertThat(chatService.isTechQuery("Spring Boot 如何配置?")).isTrue();
    }

    @Test
    void isTechQuery_whenLLMReturnsNo_returnsFalse() {
        String json = "{\"isTech\":false}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        assertThat(chatService.isTechQuery("今天天气怎么样?")).isFalse();
    }

    @Test
    void isTechQuery_whenLLMThrows_returnsTrue() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));
        assertThat(chatService.isTechQuery("query")).isTrue();
    }

    // === executeSearchAndCurate: clarification flow ===

    @Test
    void executeSearchAndCurate_vagueQuery_returnsClarification() {
        String json = "{\"isTech\":true,\"isVague\":true,\"detectedTopic\":\"Java\"," +
                "\"clarifyingQuestions\":[\"Java语法基础入门\",\"Java框架选择推荐\"]}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        ChatService.SearchResult result = chatService.executeSearchAndCurate("JAVA", 1L, 100L, emitter);

        assertThat(result.isClarification()).isTrue();
        assertThat(result.clarification().content()).contains("Java");
        assertThat(result.clarification().metadataJson()).contains("\"type\":\"clarification\"");
        // 不应调用搜索和 RAG
        verify(ragService, never()).retrieve(anyString(), anyLong());
        verify(searchAgent, never()).execute(anyString(), anyInt(), any());
    }

    @Test
    void executeSearchAndCurate_vagueQuery_persistsClarificationMessage() {
        String json = "{\"isTech\":true,\"isVague\":true,\"detectedTopic\":\"Java\"," +
                "\"clarifyingQuestions\":[\"Java入门\",\"Java框架\"]}";
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(json))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);

        chatService.executeSearchAndCurate("JAVA", 1L, 100L, emitter);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, times(2)).insert(captor.capture()); // user + assistant
        List<Message> msgs = captor.getAllValues();
        Message assistantMsg = msgs.get(1);
        assertThat(assistantMsg.getRole()).isEqualTo("assistant");
        assertThat(assistantMsg.getMetadata()).contains("clarification");
        assertThat(assistantMsg.getContent()).contains("Java");
    }

    // === executeSearchAndCurate: existing flows (unchanged behavior) ===

    @Test
    void executeSearchAndCurate_ragHits_returnsRagAnswer() {
        when(ragService.retrieve(eq("Spring Boot"), eq(1L)))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"))));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L, 100L, emitter);
        assertThat(result.isRagHit()).isTrue();
        assertThat(result.ragAnswer().answer()).contains("Spring Boot");
        verify(searchAgent, never()).execute(anyString(), anyInt(), any());
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
