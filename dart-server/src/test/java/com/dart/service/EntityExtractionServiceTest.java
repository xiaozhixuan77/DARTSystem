package com.dart.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityExtractionServiceTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;

    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        service = new EntityExtractionService(chatModel);
    }

    private void mockLlm(String text) {
        when(chatResponse.getResults()).thenReturn(List.of(new Generation(new AssistantMessage(text))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }

    @Test
    void extract_validJson_topicsFilteredByWhitelistAndConceptsExtracted() {
        mockLlm("{\"topics\":[\"Spring\",\"区块链\"],\"concepts\":[\"Redis\",\"缓存淘汰策略\",\"LRU\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Spring Boot 缓存", "## 报告\nRedis 缓存淘汰策略相关");

        // 区块链不在白名单 → 丢弃；Spring 保留
        assertThat(result.topics()).containsExactly("Spring");
        assertThat(result.concepts()).containsExactlyInAnyOrder("Redis", "缓存淘汰策略", "LRU");
    }

    @Test
    void extract_conceptsOverlappingTopics_droppedFromConcepts() {
        mockLlm("{\"topics\":[\"缓存\"],\"concepts\":[\"Redis\",\"缓存\",\"LRU\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Redis 缓存", "## 报告\nLRU 淘汰策略");

        // concepts 中与白名单同名的"缓存"归并到 topic，不重复出现
        assertThat(result.topics()).containsExactly("缓存");
        assertThat(result.concepts()).containsExactlyInAnyOrder("Redis", "LRU");
    }

    @Test
    void extract_topicsAllOffWhitelist_returnsEmptyTopics() {
        mockLlm("{\"topics\":[\"区块链\"],\"concepts\":[\"智能合约\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("区块链技术", "## 报告\n智能合约相关");

        assertThat(result.topics()).isEmpty();
        assertThat(result.concepts()).containsExactly("智能合约");
    }

    @Test
    void extract_otherTopic_keptAsFallback() {
        mockLlm("{\"topics\":[\"其他\"],\"concepts\":[\"量子计算\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("量子计算", "## 报告\n量子比特");

        assertThat(result.topics()).containsExactly("其他");
    }

    @Test
    void extract_malformedJson_returnsEmpty() {
        mockLlm("这不是 JSON 输出");

        EntityExtractionService.ExtractedEntities result =
                service.extract("查询", "## 报告\n内容");

        assertThat(result.topics()).isEmpty();
        assertThat(result.concepts()).isEmpty();
    }

    @Test
    void extract_blankResponse_returnsEmpty() {
        mockLlm("   ");

        EntityExtractionService.ExtractedEntities result =
                service.extract("查询", "## 报告\n内容");

        assertThat(result.topics()).isEmpty();
        assertThat(result.concepts()).isEmpty();
    }

    @Test
    void extract_llmException_returnsEmpty() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM timeout"));

        EntityExtractionService.ExtractedEntities result =
                service.extract("查询", "## 报告\n内容");

        assertThat(result.topics()).isEmpty();
        assertThat(result.concepts()).isEmpty();
    }

    @Test
    void extract_conceptBlankOrTooLong_dropped() {
        mockLlm("{\"topics\":[],\"concepts\":[\"\",\"  \",\"这是一个非常长的概念名词短语远超十个字限制\",\"有效概念\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("查询", "## 报告\n内容");

        assertThat(result.concepts()).containsExactly("有效概念");
    }
}
