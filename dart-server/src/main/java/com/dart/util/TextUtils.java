package com.dart.util;

/**
 * 文本匹配工具（关系抽取自环检测与跨条目标题关联共用）。
 */
public final class TextUtils {

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
}
