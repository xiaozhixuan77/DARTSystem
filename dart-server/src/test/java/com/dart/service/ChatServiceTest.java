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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

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

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatModel, searchAgent, curatorAgent, ragService,
                embeddingService, messageMapper);
    }

    @Test
    void isTechQuery_whenLLMReturnsYes_returnsTrue() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage("YES"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        assertThat(chatService.isTechQuery("Spring Boot 如何配置?")).isTrue();
    }

    @Test
    void isTechQuery_whenLLMReturnsNo_returnsFalse() {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage("NO"))));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
        assertThat(chatService.isTechQuery("今天天气怎么样?")).isFalse();
    }

    @Test
    void isTechQuery_whenLLMThrows_returnsTrue() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("timeout"));
        // 宽松模式：LLM 失败时默认为技术查询
        assertThat(chatService.isTechQuery("query")).isTrue();
    }

    @Test
    void executeSearchAndCurate_ragHits_returnsRagAnswer() {
        when(ragService.retrieve(eq("Spring Boot"), eq(1L)))
                .thenReturn(Optional.of(new RagResult("# Spring Boot 详解", List.of("https://spring.io"))));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L);
        assertThat(result.ragHit()).isTrue();
        assertThat(result.ragAnswer()).isPresent();
        assertThat(result.ragAnswer().get().answer()).contains("Spring Boot");
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

        ChatService.SearchResult result = chatService.executeSearchAndCurate("Spring Boot", 1L);
        assertThat(result.ragHit()).isFalse();
        assertThat(result.curatedReport()).isPresent();
        verify(ragService).retrieve(eq("Spring Boot"), eq(1L));
        verify(searchAgent).execute(anyString(), anyInt(), any());
        verify(curatorAgent).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_searchReturnsEmpty_returnsErrorReport() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        when(searchAgent.execute(anyString(), anyInt(), any())).thenReturn(List.of());

        ChatService.SearchResult result = chatService.executeSearchAndCurate("obscure query", 1L);
        assertThat(result.ragHit()).isFalse();
        assertThat(result.curatedReport()).isPresent();
        assertThat(result.curatedReport().get().markdown()).contains("未找到");
        verify(curatorAgent, never()).curate(anyString(), anyList(), any());
    }

    @Test
    void executeSearchAndCurate_searchAgentException_callsFallback() {
        when(ragService.retrieve(anyString(), anyLong())).thenReturn(Optional.empty());
        when(searchAgent.execute(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("network error"));

        ChatService.SearchResult result = chatService.executeSearchAndCurate("query", 1L);
        assertThat(result.curatedReport()).isPresent();
        assertThat(result.curatedReport().get().markdown()).contains("不可用");
    }
}
