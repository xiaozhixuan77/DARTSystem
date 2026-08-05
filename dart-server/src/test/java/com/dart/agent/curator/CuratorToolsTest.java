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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CuratorToolsTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private ChatResponse chatResponse;

    private CuratorTools curatorTools;

    private static final String SECTION_CONCEPT = """
            ## 📖 核心概念

            ### 1. 面向对象（OOP）
            - **定义**：基于对象封装数据与行为的编程范式，Java 的核心设计思想。
            - **机制**：通过类（Class）定义模板，实例化（new）创建对象；封装、继承、多态三大特性支撑代码复用与扩展。
            - **示例**：定义 User 类封装用户属性与行为。
            - **关联**：是理解泛型、集合框架的基础。
            """;

    private static final String SECTION_PRINCIPLE_CODE = """
            ## 🔬 关键原理

            ### 1. JVM 内存模型
            Java 程序运行在 JVM 之上，堆内存存放对象实例，栈内存存放方法调用帧。

            ## 💻 代码示例

            ### 示例1：Hello World
            ```java
            public class Hello {
                public static void main(String[] args) {
                    System.out.println("Hello Java");
                }
            }
            ```
            """;

    private static final String SECTION_FAQ_ADVANCE = """
            ## ⚠️ 常见误区与 FAQ

            **误区1：数组和 ArrayList 混用**
            正确做法：根据长度是否固定选择。

            ## 🚀 进阶学习方向

            1. 深入 JVM 调优

            ## 📚 参考文献

            1. [Java 官方文档](https://docs.oracle.com)
            """;

    private static final String SECTION_FAILED = "> ⚠️ 本节内容生成失败，请稍后重试或访问参考文献了解详情。";

    private static ScoredResource makeResource(String url, String title, String content, double score) {
        return new ScoredResource(url, title, content, 5, 4, 4, 4, score);
    }

    @BeforeEach
    void setUp() {
        curatorTools = new CuratorTools(chatModel, 0.85);
    }

    /**
     * 并行生成时调用顺序不定，按 Prompt 内容分发各节响应。
     */
    private void stubThreeSections() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            String section;
            if (text.contains("核心概念")) {
                section = SECTION_CONCEPT;
            } else if (text.contains("关键原理")) {
                section = SECTION_PRINCIPLE_CODE;
            } else {
                section = SECTION_FAQ_ADVANCE;
            }
            ChatResponse resp = new ChatResponse(
                    List.of(new Generation(new AssistantMessage(section))));
            return resp;
        });
    }

    private List<ScoredResource> fourResources() {
        return List.of(
                makeResource("https://a.com", "Resource A", "content a ".repeat(200), 4.5),
                makeResource("https://b.com", "Resource B", "content b ".repeat(200), 4.2),
                makeResource("https://c.com", "Resource C", "content c ".repeat(200), 4.0),
                makeResource("https://d.com", "Resource D", "content d ".repeat(200), 3.8));
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

    // === 分节报告生成 ===

    @Test
    void generateReport_normalFlow_returnsJoinedReport() {
        stubThreeSections();

        CuratedReport report = curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        assertThat(report).isNotNull();
        assertThat(report.markdown()).contains("Java");
        assertThat(report.markdown()).contains("📖");
        assertThat(report.markdown()).contains("📚");
    }

    @Test
    void generateReport_callsLLMThreeTimes() {
        stubThreeSections();

        curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void generateReport_sectionsInOrder() {
        stubThreeSections();

        CuratedReport report = curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        String md = report.markdown();
        int idxConcept = md.indexOf("📖");
        int idxPrinciple = md.indexOf("🔬");
        int idxCode = md.indexOf("💻");
        int idxFaq = md.indexOf("⚠️");
        int idxAdvance = md.indexOf("🚀");
        int idxRef = md.indexOf("📚");
        assertThat(idxConcept).isLessThan(idxPrinciple);
        assertThat(idxPrinciple).isLessThan(idxCode);
        assertThat(idxCode).isLessThan(idxFaq);
        assertThat(idxFaq).isLessThan(idxAdvance);
        assertThat(idxAdvance).isLessThan(idxRef);
    }

    @Test
    void generateReport_includesAllSixModules() {
        stubThreeSections();

        CuratedReport report = curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        assertThat(report.markdown()).contains("📖");
        assertThat(report.markdown()).contains("🔬");
        assertThat(report.markdown()).contains("💻");
        assertThat(report.markdown()).contains("⚠️");
        assertThat(report.markdown()).contains("🚀");
        assertThat(report.markdown()).contains("📚");
    }

    @Test
    void generateReport_includesReferencesSection() {
        stubThreeSections();

        CuratedReport report = curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        assertThat(report.markdown()).contains("参考文献");
        assertThat(report.markdown()).contains("https://docs.oracle.com");
    }

    @Test
    void generateReport_contentTruncatedAt1500() {
        stubThreeSections();

        String longContent = "ABCDEFGHIJ".repeat(200); // 2000 chars, > 1500
        ScoredResource r1 = makeResource("https://a.com", "Title A", longContent, 4.5);
        ScoredResource r2 = makeResource("https://b.com", "Title B", "short content ".repeat(20), 4.0);
        curatorTools.generateReport("test query", List.of(r1, r2), new SseEmitter(0L));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(captor.capture());
        for (Prompt p : captor.getAllValues()) {
            String promptText = p.getInstructions().getFirst().getText();
            // 旧阈值 250 字符时仅保留前 25 个重复单元，新阈值 1500 应保留 100 个以上
            assertThat(promptText).contains("ABCDEFGHIJ".repeat(100));
        }
    }

    @Test
    void generateReport_oneSectionFails_keepsOthersWithPlaceholder() {
        // 原理+代码节抛异常，其余节按 Prompt 内容正常返回
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            String text = p.getInstructions().getFirst().getText();
            if (text.contains("关键原理")) {
                throw new RuntimeException("timeout");
            }
            String section;
            if (text.contains("核心概念")) {
                section = SECTION_CONCEPT;
            } else {
                section = SECTION_FAQ_ADVANCE;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(section))));
        });

        CuratedReport report = curatorTools.generateReport("Java", fourResources(), new SseEmitter(0L));
        assertThat(report.markdown()).contains("📖");
        assertThat(report.markdown()).contains("生成失败");
        assertThat(report.markdown()).contains("📚");
        // 失败的节内容不出现
        assertThat(report.markdown()).doesNotContain("JVM 内存模型");
    }

    @Test
    void generateReport_allSectionsFail_returnsFallback() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "content ".repeat(200), 4.5);
        ScoredResource r2 = makeResource("https://medium.com", "Medium Guide", "content ".repeat(200), 4.0);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1, r2), new SseEmitter(0L));
        assertThat(report.markdown()).contains("Spring Boot");
        assertThat(report.sources()).isNotEmpty();
        assertThat(report.markdown()).contains("搜索结果");
    }

    @Test
    void generateReport_emptyInput_returnsEmptyReport() {
        CuratedReport report = curatorTools.generateReport("query", List.of(), new SseEmitter(0L));
        assertThat(report.title()).isEqualTo("query");
        assertThat(report.markdown()).contains("未找到相关内容");
    }

    @Test
    void generateReport_singleResource_skipsLLM() {
        ScoredResource r1 = makeResource("https://spring.io", "Spring Official", "Spring content ".repeat(100), 4.5);
        CuratedReport report = curatorTools.generateReport("Spring Boot", List.of(r1), new SseEmitter(0L));
        assertThat(report.markdown()).contains("Spring Boot");
        assertThat(report.markdown()).contains("Spring Official");
        verify(chatModel, times(0)).call(any(Prompt.class));
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
