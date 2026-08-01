package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Curator 工具集 — Jaccard 去重、LLM 报告生成、输出校验、降级兜底。
 */
public class CuratorTools {

    private static final Logger log = LoggerFactory.getLogger(CuratorTools.class);

    private static final int MIN_REPORT_LENGTH = 800;
    private static final int MAX_REPORT_LENGTH = 8000;
    private static final int MAX_CONTENT_PER_RESOURCE = 1500;

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
     * LLM 生成结构化 Markdown 报告。首次尝试全量资源，失败后用 Top 3 缩量重试。
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return emptyReport(query);
        }

        // 去重后仅剩 1 个 → 跳过 LLM
        if (resources.size() == 1) {
            return singleResourceReport(query, resources.getFirst());
        }

        // 首次尝试：全量资源
        try {
            String prompt = buildReportPrompt(query, resources);
            ChatOptions options = ChatOptions.builder().maxTokens(8192).build();
            String response = chatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResults()
                    .getFirst()
                    .getOutput()
                    .getText();
            String markdown = cleanAndTruncate(response);
            List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
            return new CuratedReport(query, markdown, sources, Instant.now());
        } catch (Exception e) {
            log.warn("LLM 报告生成首次尝试失败（{} 个资源），使用 Top 3 缩量重试", resources.size(), e);
        }

        // 缩量重试：仅取 Top 3 资源
        int retryCount = Math.min(3, resources.size());
        List<ScoredResource> topResources = resources.subList(0, retryCount);
        try {
            String prompt = buildReportPrompt(query, topResources);
            ChatOptions options = ChatOptions.builder().maxTokens(8192).build();
            String response = chatModel.call(new Prompt(new UserMessage(prompt), options))
                    .getResults()
                    .getFirst()
                    .getOutput()
                    .getText();
            String markdown = cleanAndTruncate(response);
            // 报告基于 Top 3 生成，但来源列表保留全部资源的 URL
            List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
            log.info("LLM 报告缩量重试成功（{} 个资源）", retryCount);
            return new CuratedReport(query, markdown, sources, Instant.now());
        } catch (Exception e2) {
            log.warn("LLM 报告缩量重试也失败，降级使用 fallbackReport", e2);
            return fallbackReport(query, resources);
        }
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

    private String buildReportPrompt(String query, List<ScoredResource> resources) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个技术教育专家。请根据以下搜索结果，生成一份结构化的知识学习报告。

                搜索主题：""").append(query).append("""



                ## 输出结构（严格按以下顺序）

                ### 1. 知识全景 — Mermaid 思维导图
                用 ```mermaid
                mindmap``` 语法绘制知识全景图。
                - 根节点为搜索主题
                - 一级分支 3-5 个（主要知识方向）
                - 每个一级分支下 2-4 个二级节点（具体知识点）
                - 仅输出 mermaid 代码块即可

                ### 2. 📖 核心概念
                - 从搜索结果中提取 3-6 个关键概念
                - 每个概念给出：简明定义 + 关键特征 + 适用场景
                - 标注概念之间的关联（如"A是B的基础""A与C互补"）

                ### 3. 🔬 关键原理
                - 选取 2-4 个核心技术原理深入讲解
                - 用通俗语言解释，避免直接复制原文
                - 若信息不足标注 [信息不足，建议访问原文]

                ### 4. 💻 代码示例
                - 提供 2-3 个可直接运行的代码示例
                - 代码块标注语言类型
                - 每个示例带一行注释说明用途
                - 若搜索结果缺少代码，根据常识补充

                ### 5. ⚠️ 常见误区与 FAQ
                - 3-5 个该主题下开发者常犯的误区，每个附正确做法
                - 2-3 个常见问答

                ### 6. 🚀 进阶学习方向
                - 3-4 个与该主题相关的进阶方向
                - 每个方向一句话描述学习目标

                ### 7. 📚 参考文献
                - 列出本次使用的所有来源
                - 格式：序号. [标题](URL)

                ## 写作规范
                - 总字数 2000-4000 字
                - 中文撰写，技术术语保留英文原名
                - 正文自包含，可独立阅读，不要在正文中引用"资源1""资源2"等编号
                - 不要以"根据搜索结果"等开头
                - 信息不足处标注 [信息不足]
                - 直接输出 Markdown，不要额外说明

                ---

                搜索结果（按质量评分排序）：

                """);

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

    private String cleanAndTruncate(String markdown) {
        String cleaned = markdown.trim();
        // 移除以"根据搜索结果"等开头的行
        cleaned = cleaned.replaceFirst("^根据搜索结果[^\\n]*\\n*", "");
        cleaned = cleaned.replaceFirst("^根据提供的搜索结果[^\\n]*\\n*", "");
        if (cleaned.length() > MAX_REPORT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_REPORT_LENGTH) + "\n\n> 报告已截断以控制长度。";
        }
        return cleaned;
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
