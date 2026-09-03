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
                service.extract("Spring Boot 缓存", "## 报告\nRedis 缓存淘汰策略 LRU 相关");

        // 区块链不在白名单 → 丢弃；Spring 保留；概念上限 2：按 LLM 输出顺序取前 2
        assertThat(result.topics()).containsExactly("Spring");
        assertThat(result.concepts()).containsExactlyInAnyOrder("Redis", "缓存淘汰策略");
    }

    @Test
    void extract_conceptsOverlappingTopics_droppedFromConcepts() {
        mockLlm("{\"topics\":[\"缓存\"],\"concepts\":[\"Redis\",\"缓存\",\"LRU\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Redis 缓存", "## 报告\nRedis LRU 淘汰策略");

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
    void extract_otherTopic_filteredOut() {
        mockLlm("{\"topics\":[\"其他\"],\"concepts\":[\"量子计算\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("量子计算", "## 报告\n量子计算 量子比特");

        // 兜底主题"其他"不再是合法主题（用户确认：减少兜底实体），概念保留
        assertThat(result.topics()).isEmpty();
        assertThat(result.concepts()).containsExactly("量子计算");
    }

    @Test
    void extract_moreThanMaxConcepts_truncated() {
        mockLlm("{\"topics\":[\"缓存\"],\"concepts\":[\"Redis\",\"LRU\",\"缓存淘汰策略\",\"过期策略\",\"缓存击穿\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("缓存专题", "## 报告\nRedis LRU 缓存淘汰策略 过期策略 缓存击穿");

        // 概念数量上限 2：截断取前 2（按 LLM 输出顺序）
        assertThat(result.concepts()).containsExactly("Redis", "LRU");
    }

    @Test
    void extract_stopWordConcept_dropped() {
        mockLlm("{\"topics\":[\"Java\"],\"concepts\":[\"基础语法\",\"Java 垃圾回收\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Java 基础", "## 报告\nJava 垃圾回收 基础语法章节");

        // 壳词"基础语法"精确匹配停用词表 → 丢弃；具体技术名词保留
        assertThat(result.concepts()).containsExactly("Java 垃圾回收");
    }

    @Test
    void extract_moreThanMaxTopics_truncated() {
        mockLlm("{\"topics\":[\"Spring\",\"Java\",\"缓存\"],\"concepts\":[\"Redis\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Java Spring 缓存", "## 报告\nRedis 相关");

        // 主题数量上限 2：即使所有 topic 均在白名单也硬截断取前 2（prompt 要求 1-2 的 enforce）
        assertThat(result.topics()).containsExactly("Spring", "Java");
    }

    @Test
    void extract_extendedStopWordConcept_dropped() {
        mockLlm("{\"topics\":[\"Java\"],\"concepts\":[\"详解\",\"Java 垃圾回收\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Java 详解", "## 报告\nJava 垃圾回收 详解");

        // 扩充停用词"详解"精确匹配 → 丢弃；具体技术名词保留
        assertThat(result.concepts()).containsExactly("Java 垃圾回收");
    }

    @Test
    void extract_conceptNotInReport_dropped() {
        mockLlm("{\"topics\":[\"缓存\"],\"concepts\":[\"Redis\",\"量子计算\"]}");

        EntityExtractionService.ExtractedEntities result =
                service.extract("Redis 缓存", "## 报告\nRedis 缓存淘汰策略");

        // 概念必须出现在报告正文（准确度校验）：LLM 编造的"量子计算"被丢弃
        assertThat(result.concepts()).containsExactly("Redis");
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
                service.extract("查询", "## 报告\n有效概念内容");

        assertThat(result.concepts()).containsExactly("有效概念");
    }
}
