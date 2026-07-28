package com.dart.agent.curator;

import com.dart.agent.scoring.ScoredResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Curator 工具集 — Jaccard 去重、LLM 报告生成、输出校验、降级兜底。
 */
public class CuratorTools {

    private static final Logger log = LoggerFactory.getLogger(CuratorTools.class);

    private static final int MIN_REPORT_LENGTH = 200;
    private static final int MAX_REPORT_LENGTH = 12000;
    private static final int MAX_CONTENT_PER_RESOURCE = 800;

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
     * LLM 生成结构化 Markdown 报告。
     */
    public CuratedReport generateReport(String query, List<ScoredResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return emptyReport(query);
        }

        // 去重后仅剩 1 个 → 跳过 LLM
        if (resources.size() == 1) {
            return singleResourceReport(query, resources.getFirst());
        }

        String prompt = buildReportPrompt(query, resources);
        try {
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults()
                    .getFirst()
                    .getOutput()
                    .getText();
            String markdown = cleanAndTruncate(response);
            List<String> sources = resources.stream().map(ScoredResource::getUrl).toList();
            return new CuratedReport(query, markdown, sources, Instant.now());
        } catch (Exception e) {
            log.warn("LLM 报告生成失败，降级使用 fallbackReport", e);
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
            sb.append("- **摘要**: ").append(truncateContent(r.getContent(), 300)).append("\n\n");
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
        sb.append("你是一个技术资源整理专家。请根据以下搜索结果，生成一份结构化的 Markdown 技术学习报告。\n\n");
        sb.append("搜索主题：").append(query).append("\n\n");
        sb.append("要求：\n");
        sb.append("1. 使用 # 标题开始，按章节组织内容\n");
        sb.append("2. 每个资源条目包含来源 URL\n");
        sb.append("3. 引用评分数据时不调整数值\n");
        sb.append("4. 不以[根据搜索结果]等开头\n");
        sb.append("5. 信息不足时标注[信息不足，建议访问原文]\n");
        sb.append("6. 代码块标注语言类型\n\n");

        sb.append("---\n\n");
        sb.append("搜索结果（按质量评分排序）：\n\n");

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
