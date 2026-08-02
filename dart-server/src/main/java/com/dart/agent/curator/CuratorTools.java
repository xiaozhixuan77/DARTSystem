package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import com.dart.util.SseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Curator 工具集 — Jaccard 去重、分节 LLM 报告生成、输出校验、降级兜底。
 */
public class CuratorTools {

    private static final Logger log = LoggerFactory.getLogger(CuratorTools.class);

    private static final int MIN_REPORT_LENGTH = 800;
    private static final int MAX_REPORT_LENGTH = 16000;
    private static final int MAX_CONTENT_PER_RESOURCE = 1500;
    private static final int MAX_TOKENS_PER_SECTION = 8192;

    private static final String SECTION_FAILED =
            "> ⚠️ 本节内容生成失败，请稍后重试或访问参考文献了解详情。";

    private final ChatModel chatModel;
    private final double jaccardThreshold;

    public CuratorTools(ChatModel chatModel, double jaccardThreshold) {
        this.chatModel = chatModel;
        this.jaccardThreshold = jaccardThreshold;
    }

    /**
     * 基于标题 Jaccard 相似度去重，高分保留。
     */
    public List<ScoredResource> deduplicate(List<ScoredResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        if (resources.size() == 1) {
            return new ArrayList<>(resources);
        }

        List<ScoredResource> sorted = new ArrayList<>(resources);
        sorted.sort(Comparator.comparingDouble(ScoredResource::getWeightedScore).reversed());

        // 预计算 shingle 集，避免 O(n²) 次重复计算
        Map<ScoredResource, Set<String>> shingleCache = new HashMap<>();
        for (ScoredResource r : sorted) {
            shingleCache.put(r, shingle(r.getTitle()));
        }

        List<ScoredResource> result = new ArrayList<>();
        for (ScoredResource r : sorted) {
            boolean duplicate = false;
            Set<String> shinglesA = shingleCache.get(r);
            for (ScoredResource kept : result) {
                double sim = jaccardSimilarity(shinglesA, shingleCache.get(kept));
                if (sim >= jaccardThreshold) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * 分节生成结构化 Markdown 学习报告：
     * ①思维导图+学习目标 → ②核心概念 → ③原理+代码示例 → ④误区FAQ+进阶+参考文献。
     * 每节独立 LLM 调用（单节失败仅占位，不影响其余节），拼接后返回。
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources, SseEmitter emitter) {
        if (resources == null || resources.isEmpty()) {
            return emptyReport(query);
        }

        // 去重后仅剩 1 个 → 跳过 LLM
        if (resources.size() == 1) {
            return singleResourceReport(query, resources.getFirst());
        }

        String resourcesText = buildResourcesText(resources);
        List<String> sections = new ArrayList<>();

        // 节 1：思维导图 + 学习目标（后续节的上下文锚点）
        SseUtils.sendStatus(emitter, "curating", "正在绘制知识全景...");
        String mindmapSection = generateSection(query, resourcesText, "",
                buildMindmapPrompt(query), "mindmap");
        sections.add(mindmapSection);

        // 节 2：核心概念（携带思维导图锚点）
        SseUtils.sendStatus(emitter, "curating", "正在讲解核心概念...");
        String conceptSection = generateSection(query, resourcesText, mindmapSection,
                buildConceptPrompt(query), "concepts");
        sections.add(conceptSection);

        // 节 3：关键原理 + 代码示例
        SseUtils.sendStatus(emitter, "curating", "正在解析原理与代码示例...");
        String principleCodeSection = generateSection(query, resourcesText, mindmapSection,
                buildPrincipleCodePrompt(query), "principles_code");
        sections.add(principleCodeSection);

        // 节 4：误区 FAQ + 进阶方向 + 参考文献
        SseUtils.sendStatus(emitter, "curating", "正在整理误区与进阶方向...");
        String faqAdvanceSection = generateSection(query, resourcesText, mindmapSection,
                buildFaqAdvancePrompt(query), "faq_advance");
        sections.add(faqAdvanceSection);

        // 所有节全部失败 → 降级为纯搜索结果列表
        if (sections.stream().allMatch(s -> s.equals(SECTION_FAILED))) {
            log.warn("所有报告节生成失败，降级使用 fallbackReport");
            return fallbackReport(query, resources);
        }

        String markdown = truncateReport(String.join("\n\n---\n\n", sections));
        List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
        return new CuratedReport(query, markdown, sources, Instant.now());
    }

    /**
     * 调用 LLM 生成单节内容；失败返回占位文本，不影响主流程。
     */
    private String generateSection(String query, String resourcesText, String mindmapAnchor,
                                   String sectionPrompt, String sectionName) {
        try {
            ChatOptions options = ChatOptions.builder().maxTokens(MAX_TOKENS_PER_SECTION).build();
            String prompt = buildSectionPrompt(query, resourcesText, mindmapAnchor, sectionPrompt);
            String response = chatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResults()
                    .getFirst()
                    .getOutput()
                    .getText();
            return cleanSection(response);
        } catch (Exception e) {
            log.warn("报告节[{}]生成失败，使用占位文本", sectionName, e);
            return SECTION_FAILED;
        }
    }

    private String buildSectionPrompt(String query, String resourcesText, String mindmapAnchor,
                                      String sectionPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个技术教育专家，正在撰写关于「").append(query)
                .append("」的深度学习报告。基于以下搜索结果素材撰写。\n\n");
        if (mindmapAnchor != null && !mindmapAnchor.isBlank()) {
            sb.append("之前已生成的知识全景（用于对齐结构，勿重复绘制或互相矛盾）：\n")
                    .append(mindmapAnchor).append("\n\n");
        }
        sb.append(sectionPrompt).append("\n\n---\n\n").append(resourcesText);
        return sb.toString();
    }

    private String buildMindmapPrompt(String query) {
        return """
                请输出以下两个部分：

                ## 🧭 知识全景

                用 ```mermaid
                mindmap``` 语法绘制知识全景图：
                - 根节点为「%s」
                - 一级分支 3-5 个（主要知识方向）
                - 每个一级分支下 2-4 个二级节点（具体知识点）
                - 仅输出 mermaid 代码块

                ## 🎯 学习目标

                列出 3-5 条"学完本节你将掌握/能做"的目标，让读者明确学习任务。
                每条一行，以"- "开头。

                本节字数控制在 400-800 字（不含 mermaid 代码）。
                """.formatted(query);
    }

    private String buildConceptPrompt(String query) {
        return """
                请输出以下章节（只输出本节，不要输出其他章节）：

                ## 📖 核心概念

                从搜索素材中提取 3-6 个「%s」主题下的关键概念，每个概念展开 300-500 字：

                ### 1. 概念名
                - **定义**：一句话简明定义
                - **机制**：深入讲解其原理与内部工作方式（本部分为重点，要展开讲透）
                - **示例**：简短例子帮助理解
                - **关联**：与主题及其他概念的关系

                概念之间要有衔接过渡，读者学完后能独立理解该主题的知识骨架。
                本节字数控制在 2000-3500 字。
                """.formatted(query);
    }

    private String buildPrincipleCodePrompt(String query) {
        return """
                请输出以下两个章节（只输出本节，不要输出其他章节）：

                ## 🔬 关键原理

                选取 2-4 个「%s」核心技术原理，每个展开 300-500 字：
                - 用通俗语言解释，避免直接复制原文
                - 描述过程/步骤/机制（如执行流程、内部结构）
                - 信息不足处标注 [信息不足，建议访问原文]

                ## 💻 代码示例

                提供 2-3 个可直接运行的示例，每个包含：
                ### 示例N：标题
                - 代码块（标注语言类型，代码内加注释）
                - **讲解**：逐段解释关键代码的作用
                - **运行结果**：说明预期输出或效果

                若搜索素材缺少代码，根据常识补充完整可运行的示例。
                本节字数控制在 2500-4000 字（含代码）。
                """.formatted(query);
    }

    private String buildFaqAdvancePrompt(String query) {
        return """
                请输出以下三个章节（只输出本节，不要输出其他章节）：

                ## ⚠️ 常见误区与 FAQ

                - 3-5 个「%s」主题下开发者常犯的误区，每个格式：
                  **误区N：错误做法**
                  错误原因 + 正确做法
                - 2-3 个常见问答（**Q1: ...** / **A1: ...**）

                ## 🚀 进阶学习方向

                3-4 个与该主题相关的进阶方向，每个一行：
                - 方向名：一句话描述学习目标与路径

                ## 📚 参考文献

                列出本次报告使用的所有来源，格式：
                1. [标题](URL)

                本节字数控制在 2000-3500 字。
                """.formatted(query);
    }

    /**
     * 拼接资源摘要文本（每资源 ≤ 1500 字符）。
     */
    private String buildResourcesText(List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (ScoredResource r : resources) {
            sb.append("## 资源 ").append(idx).append(": ").append(r.getTitle()).append("\n");
            sb.append("- URL: ").append(r.getUrl()).append("\n");
            sb.append("- 评分: T").append(r.getScoreT())
                    .append("/A").append(r.getScoreA())
                    .append("/D").append(r.getScoreD())
                    .append("/M").append(r.getScoreM())
                    .append(" (总分 ").append(String.format("%.2f", r.getWeightedScore())).append(")\n");
            sb.append("- 内容摘要: ").append(truncateContent(r.getContent(), MAX_CONTENT_PER_RESOURCE)).append("\n\n");
            idx++;
        }
        return sb.toString();
    }

    /**
     * 6 项输出校验。
     */
    public boolean validateReport(CuratedReport report) {
        if (report == null) return false;
        if (report.title() == null || report.title().isBlank()) return false;
        if (report.markdown() == null || report.markdown().length() < MIN_REPORT_LENGTH) return false;
        if (report.sources() == null || report.sources().isEmpty()) return false;
        if (report.generatedAt() == null) return false;
        // 至少包含一个 # 标题
        if (!report.markdown().contains("#")) return false;
        return true;
    }

    /**
     * 降级报告 —— 纯代码拼接，不依赖 LLM。
     */
    public CuratedReport fallbackReport(String query, List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(query).append(" 搜索结果\n\n");
        sb.append("> AI 报告生成暂时不可用，以下为按质量评分排序的原始搜索结果。\n\n");

        int rank = 1;
        for (ScoredResource r : resources) {
            sb.append("## ").append(rank).append(". ").append(r.getTitle())
                    .append(" ⭐").append(String.format("%.1f", r.getWeightedScore()))
                    .append("\n\n");
            sb.append("- **来源**: ").append(r.getUrl()).append("\n");
            sb.append("- **质量评分**: T:").append(r.getScoreT())
                    .append(" A:").append(r.getScoreA())
                    .append(" D:").append(r.getScoreD())
                    .append(" M:").append(r.getScoreM()).append("\n");
            sb.append("- **摘要**: ").append(truncateContent(r.getContent(), MAX_CONTENT_PER_RESOURCE)).append("\n\n");
            rank++;
        }

        List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
        return new CuratedReport(query, sb.toString(), sources, Instant.now());
    }

    /**
     * 计算两个文本的 Jaccard 相似度（基于 3-gram）。
     */
    double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        return jaccardSimilarity(shingle(a), shingle(b));
    }

    private double jaccardSimilarity(Set<String> shinglesA, Set<String> shinglesB) {
        if (shinglesA.isEmpty() || shinglesB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(shinglesA);
        intersection.retainAll(shinglesB);
        Set<String> union = new HashSet<>(shinglesA);
        union.addAll(shinglesB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> shingle(String text) {
        Set<String> result = new HashSet<>();
        String t = text.toLowerCase().replaceAll("\\s+", " ").trim();
        for (int i = 0; i <= t.length() - 3; i++) {
            result.add(t.substring(i, i + 3));
        }
        return result;
    }

    private String cleanSection(String markdown) {
        String cleaned = markdown.trim();
        // 移除以"根据搜索结果"等开头的行
        cleaned = cleaned.replaceFirst("^根据搜索结果[^\\n]*\\n*", "");
        cleaned = cleaned.replaceFirst("^根据提供的搜索结果[^\\n]*\\n*", "");
        return cleaned;
    }

    private String truncateReport(String markdown) {
        if (markdown == null || markdown.length() <= MAX_REPORT_LENGTH) return markdown;
        return markdown.substring(0, MAX_REPORT_LENGTH) + "\n\n> 报告已截断以控制长度。";
    }

    private static String truncateContent(String content, int maxLen) {
        if (content == null || content.isEmpty()) return "";
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    private CuratedReport emptyReport(String query) {
        return new CuratedReport(query,
                "# " + query + "\n\n未找到相关内容，请尝试更换搜索关键词。",
                List.of(), Instant.now());
    }

    private CuratedReport singleResourceReport(String query, ScoredResource r) {
        String md = "# " + query + " 搜索结果\n\n"
                + "## " + r.getTitle() + "\n\n"
                + "- **来源**: " + r.getUrl() + "\n"
                + "- **内容**: " + truncateContent(r.getContent(), 1000) + "\n";
        return new CuratedReport(query, md, List.of(r.getUrl()), Instant.now());
    }
}
