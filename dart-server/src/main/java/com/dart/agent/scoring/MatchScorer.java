package com.dart.agent.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语义匹配评分：基于关键词命中率评估查询与页面内容的匹配度（1-5）。
 * 不使用 LLM，速度极快（每页 < 1ms），适合对搜索结果的实时评分场景。
 */
public class MatchScorer {

    private static final Logger log = LoggerFactory.getLogger(MatchScorer.class);

    public MatchScorer() {
        // 无 LLM 依赖，纯轻量计算
    }

    /**
     * 基于关键词命中率和标题匹配计算得分 1-5。
     */
    public int evaluate(String query, String content) {
        if (query == null || query.isBlank()) return 1;
        if (content == null || content.isBlank()) return 1;

        String lowerQuery = query.toLowerCase();
        String lowerContent = content.toLowerCase();

        // 分词：按空格和常见分隔符拆分
        String[] keywords = lowerQuery.split("[\\s,，.。、;；:：!！?？]+");
        if (keywords.length == 0) return 1;

        // 计算关键词命中率
        int hits = 0;
        int total = 0;
        for (String kw : keywords) {
            if (kw.length() < 2) continue; // 跳过大短的词
            total++;
            if (lowerContent.contains(kw)) hits++;
        }

        if (total == 0) return 1;

        double hitRate = (double) hits / total;

        // 额外加分：标题中出现查询词（content 的前 200 字符视为标题区域）
        String titleRegion = content.length() > 200 ? content.substring(0, 200).toLowerCase() : lowerContent;
        boolean titleMatch = titleRegion.contains(lowerQuery) ||
                keywords.length >= 2 && titleRegion.contains(keywords[0]) && titleRegion.contains(keywords[1]);

        // 评分映射
        if (hitRate >= 0.8 && titleMatch) return 5;
        if (hitRate >= 0.8) return 4;
        if (hitRate >= 0.6) return 4;
        if (hitRate >= 0.4) return 3;
        if (hitRate >= 0.2) return 2;
        return 1;
    }
}
