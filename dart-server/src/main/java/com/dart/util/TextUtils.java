package com.dart.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本匹配工具（关系抽取自环检测与跨条目标题关联共用）。
 */
public final class TextUtils {

    private static final Pattern EN_TOKEN = Pattern.compile("[a-z0-9]{2,}");
    private static final Pattern STOP_WORDS = Pattern.compile(
            "(的|和|与|中|是|为|对|在|用|或|及|并|而|之|其|于|有|这|那|什么|怎么|如何|可以|进行|以及|相关)+");
    private static final Pattern NON_CHINESE = Pattern.compile("[^一-龥]+");

    private TextUtils() {
    }

    /**
     * 大小写不敏感互相包含（双向 contains，子串长度 ≥2 防单字符误报；相等返回 true）。
     */
    public static boolean containsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String la = a.toLowerCase();
        String lb = b.toLowerCase();
        if (la.equals(lb)) {
            return true;
        }
        return (la.contains(lb) && lb.length() >= 2) || (lb.contains(la) && la.length() >= 2);
    }

    /**
     * 关键词提取（MatchScorer 评分与跨条目标题关联共用）：
     * 英文/数字 token（≥2 字符，大小写不敏感）+ 中文按停用词切分的片段（≥2 字符）。
     * 中文片段剔除标点（防"自动配置原理？"与"自动配置原理"因标点不同不匹配）。
     */
    public static List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lower = query.toLowerCase();
        List<String> keywords = new ArrayList<>();
        Matcher en = EN_TOKEN.matcher(lower);
        while (en.find()) {
            String tok = en.group();
            if (!keywords.contains(tok)) {
                keywords.add(tok);
            }
        }
        String chinesePart = NON_CHINESE.matcher(lower).replaceAll("");
        for (String seg : STOP_WORDS.split(chinesePart)) {
            String s = seg.trim();
            if (s.length() >= 2 && !keywords.contains(s)) {
                keywords.add(s);
            }
        }
        return keywords;
    }
}
