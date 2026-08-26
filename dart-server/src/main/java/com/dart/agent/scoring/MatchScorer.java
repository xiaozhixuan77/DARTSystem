package com.dart.agent.scoring;

import com.dart.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 语义匹配评分：基于关键词命中率评估查询与页面内容的匹配度（1-5）。
 * 不使用 LLM，速度极快（每页 < 1ms），适合对搜索结果的实时评分场景。
 *
 * 分词策略：英文/数字 token（≥2 字符）+ 中文按停用词切分的片段（≥2 字符），
 * 关键词提取共用 TextUtils.extractKeywords（与跨条目标题关联一致）。
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

        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) return 1;

        String lowerContent = content.toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            if (lowerContent.contains(kw)) hits++;
        }
        double hitRate = (double) hits / keywords.size();

        // 额外加分：标题中出现查询词（content 的前 200 字符视为标题区域）
        String titleRegion = content.length() > 200 ? content.substring(0, 200).toLowerCase() : lowerContent;
        boolean titleMatch = titleRegion.contains(query.toLowerCase()) ||
                keywords.size() >= 2 && titleRegion.contains(keywords.get(0)) && titleRegion.contains(keywords.get(1));

        // 评分映射（0.6-0.8 区间与 ≥0.8 无标题加分同档 4，原 ≥0.8 分支为死分支已合并）
        if (hitRate >= 0.8 && titleMatch) return 5;
        if (hitRate >= 0.6) return 4;
        if (hitRate >= 0.4) return 3;
        if (hitRate >= 0.2) return 2;
        return 1;
    }

    private static List<String> extractKeywords(String query) {
        return TextUtils.extractKeywords(query);
    }
}
