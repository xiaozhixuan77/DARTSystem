package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
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
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuratorToolsTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;

    private CuratorTools curatorTools;

    private static final String VALID_REPORT = """
            ## 🧭 知识全景

            ```mermaid
            mindmap
              root((Spring Boot))
                核心概念
                  自动配置
                  起步依赖
                关键原理
                  IoC容器
                  条件装配
            ```

            ---

            ## 📖 核心概念

            ### 自动配置
            Spring Boot 根据类路径依赖自动配置应用组件...

            ### 起步依赖
            起步依赖是一组预定义的 Maven/Gradle 依赖集合...

            ---

            ## 🔬 关键原理

            ### IoC 容器原理
            Spring Boot 的 IoC 容器通过依赖注入管理 Bean 的生命周期...

            ---

            ## 💻 代码示例

            ```java
            @SpringBootApplication
            public class DemoApplication {
                public static void main(String[] args) {
                    SpringApplication.run(DemoApplication.class, args);
                }
            }
            ```

            ---

            ## ⚠️ 常见误区与 FAQ

            **误区1：Spring Boot 不需要理解 Spring**
            实际上理解 Spring 核心原理能帮你更好地排查自动配置问题...

            **Q1: Spring Boot 和 Spring MVC 的关系？**
            Spring Boot 是脚手架，Spring MVC 是 Web 框架...

            ---

            ## 🚀 进阶学习方向

            1. Spring Cloud 微服务体系
            2. Spring Security 安全框架

            ---

            ## 📚 参考文献

            1. [Spring Boot 官方文档](https://spring.io)
            2. [Medium Guide](https://medium.com)
            """;

    private static ScoredResource makeResource(String url, String title, String content, double score) {
        return new ScoredResource(url, title, content, 5, 4, 4, 4, score);
    }

    @BeforeEach
    void setUp() {
        curatorTools = new CuratorTools(chatModel, 0.85);
    }

    // === 去重 ===

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

    // === 报告生成 ===

    @Test
    void generateReport_normalFlow_returnsReport() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(VALID_REPORT))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(200), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot学习", List.of(r1, r2));
        assertThat(report).isNotNull();
        assertThat(report.markdown()).contains("Spring Boot");
    }

    @Test
    void generateReport_includesMermaidMindmap() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(VALID_REPORT))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        ScoredResource r1 = makeResource("https://a.com", "Resource A", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://b.com", "Resource B", "content ".repeat(200), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1, r2));
        assertThat(report.markdown()).contains("```mermaid");
        assertThat(report.markdown()).contains("mindmap");
    }

    @Test
    void generateReport_includesAllSixModules() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(VALID_REPORT))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        ScoredResource r1 = makeResource("https://a.com", "Resource A", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://b.com", "Resource B", "content ".repeat(200), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1, r2));
        assertThat(report.markdown()).contains("📖");
        assertThat(report.markdown()).contains("🔬");
        assertThat(report.markdown()).contains("💻");
        assertThat(report.markdown()).contains("⚠️");
        assertThat(report.markdown()).contains("🚀");
        assertThat(report.markdown()).contains("📚");
    }

    @Test
    void generateReport_includesReferencesSection() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(VALID_REPORT))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(200), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1, r2));
        assertThat(report.markdown()).contains("参考文献");
        assertThat(report.markdown()).contains("https://spring.io");
    }

    @Test
    void generateReport_contentTruncatedAt1500() {
        when(chatResponse.getResults()).thenReturn(
                List.of(new Generation(new AssistantMessage(VALID_REPORT))));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        String longContent = "ABCDEFGHIJ".repeat(200); // 2000 chars, > 1500
        ScoredResource r1 = makeResource("https://a.com", "Title A", longContent, 4.5);
        ScoredResource r2 = makeResource("https://b.com", "Title B", "short content ".repeat(20), 4.0);
        curatorTools.generateReport("test query", List.of(r1, r2));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String promptText = captor.getValue().getInstructions().getFirst().getText();
        // 旧阈值 250 字符时仅保留前 25 个重复单元，新阈值 1500 应保留 100 个以上
        assertThat(promptText).contains("ABCDEFGHIJ".repeat(100));
    }

    @Test
    void generateReport_llmThrows_returnsFallback() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(200), 4.0);
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
    void generateReport_singleResource_skipsLLM() {
        // 单资源不调用 LLM，直接生成报告
        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "Spring content ".repeat(100), 4.5);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1));
        assertThat(report.markdown()).contains("Spring Boot");
        assertThat(report.markdown()).contains("Spring Official");
    }

    // === 验证 ===

    @Test
    void validateReport_allChecksPass_returnsTrue() {
        CuratedReport report = new CuratedReport("报告标题", "# 报告内容\n\n## 资源列表\n内容足够长且通过800字符阈值校验 ".repeat(30),
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isTrue();
    }

    @Test
    void validateReport_titleEmpty_returnsFalse() {
        CuratedReport report = new CuratedReport("", "# Valid content ".repeat(40),
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    @Test
    void validateReport_markdownTooShort_returnsFalse() {
        // 新阈值 800 字符，此处仅 ~600 字符
        CuratedReport report = new CuratedReport("Title", "# Content ".repeat(20),
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    @Test
    void validateReport_minLengthIs800() {
        // 刚好 800 字符应该通过
        String content800 = "# x\n\n" + "B".repeat(795); // ~800 chars total
        CuratedReport report = new CuratedReport("Title", content800,
                List.of("https://a.com"), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isTrue();
    }

    @Test
    void validateReport_sourcesEmpty_returnsFalse() {
        CuratedReport report = new CuratedReport("Title", "# Content ".repeat(40),
                List.of(), java.time.Instant.now());
        assertThat(curatorTools.validateReport(report)).isFalse();
    }

    // === 降级报告 ===

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
