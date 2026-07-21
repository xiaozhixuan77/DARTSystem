package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
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
class CuratorToolsTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;

    private CuratorTools curatorTools;

    private static ScoredResource makeResource(String url, String title, String content, double score) {
        return new ScoredResource(url, title, content, 5, 4, 4, 4, score);
    }

    @BeforeEach
    void setUp() {
        curatorTools = new CuratorTools(chatModel, 0.85);
    }

    @Test
    void deduplicate_mergesHighlySimilarResources() {
        ScoredResource r1 = makeResource("https://a.com/page1", "Spring Boot入门教程", "content1 ".repeat(50), 4.5);
        ScoredResource r2 = makeResource("https://b.com/page2", "Spring Boot入门教程详解", "content2 ".repeat(50), 3.5);
        List<ScoredResource> deduped = curatorTools.deduplicate(List.of(r1, r2));
        assertThat(deduped).hasSize(1);
        assertThat(deduped.get(0).getUrl()).isEqualTo("https://a.com/page1");
    }

    @Test
    void deduplicate_keepsDistinctResources() {
        ScoredResource r1 = makeResource("https://a.com", "Spring Boot 教程", "c1 ".repeat(50), 4.5);
        ScoredResource r2 = makeResource("https://b.com", "React Hooks 原理分析", "c2 ".repeat(50), 4.0);
        List<ScoredResource> deduped = curatorTools.deduplicate(List.of(r1, r2));
        assertThat(deduped).hasSize(2);
    }

    @Test
    void deduplicate_withIdenticalTitles_keepsHigherScore() {
        ScoredResource high = makeResource("https://a.com", "完全相同的标题", "contentA ".repeat(50), 4.8);
        ScoredResource low = makeResource("https://b.com", "完全相同的标题", "contentB ".repeat(50), 3.2);
        List<ScoredResource> deduped = curatorTools.deduplicate(List.of(low, high));
        assertThat(deduped).hasSize(1);
        assertThat(deduped.get(0).getUrl()).isEqualTo("https://a.com");
    }

    @Test
    void generateReport_normalFlow_returnsReport() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(
                        "# Spring Boot 学习报告\n\n## 1. 官方文档\n来源: https://spring.io\n内容摘要"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(100), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(100), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot学习", List.of(r1, r2));
        assertThat(report).isNotNull();
        assertThat(report.markdown()).contains("Spring Boot");
    }

    @Test
    void generateReport_llmThrows_returnsFallback() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(100), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(100), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1, r2));
        assertThat(report.markdown()).contains("Spring Boot");
        assertThat(report.sources()).isNotEmpty();
    }

    @Test
    void generateReport_emptyInput_returnsEmptyReport() {
        CuratedReport report = curatorTools.generateReport("query", List.of());
        assertThat(report.title()).isEqualTo("query");
        assertThat(report.markdown()).contains("未找到相关内容");
    }

    @Test
    void validateReport_allChecksPass_returnsTrue() {
        CuratedReport report = new CuratedReport("报告标题", "# 报告内容\n\n## 资源列表\n内容足够长 ".repeat(10),
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isTrue();
    }

    @Test
    void validateReport_titleEmpty_returnsFalse() {
        CuratedReport report = new CuratedReport("", "# Valid content ".repeat(20),
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    @Test
    void validateReport_markdownTooShort_returnsFalse() {
        CuratedReport report = new CuratedReport("Title", "短",
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    @Test
    void validateReport_sourcesEmpty_returnsFalse() {
        CuratedReport report = new CuratedReport("Title", "# Content ".repeat(20),
                List.of(), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    @Test
    void fallbackReport_generatesStructuredReport() {
        ScoredResource r1 = makeResource("https://a.com", "Title A", "Content A ".repeat(50), 4.5);
        ScoredResource r2 = makeResource("https://b.com", "Title B", "Content B ".repeat(50), 3.8);
        CuratedReport report = curatorTools.fallbackReport("Spring Boot", List.of(r1, r2));
        assertThat(report.title()).isEqualTo("Spring Boot");
        assertThat(report.markdown()).contains("# Spring Boot 搜索结果");
        assertThat(report.markdown()).contains("Title A");
        assertThat(report.markdown()).contains("Title B");
        assertThat(report.sources()).contains("https://a.com", "https://b.com");
    }
}
