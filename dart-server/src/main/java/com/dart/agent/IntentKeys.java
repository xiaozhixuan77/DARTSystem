package com.dart.agent;

/**
 * 查询意图枚举值（前置判断产出，搜索量/报告风格/沉淀标签三处共用同一键集）。
 */
public final class IntentKeys {

    public static final String QUICK_FACT = "quick_fact";
    public static final String HOW_TO = "how_to";
    public static final String DEEP_DIVE = "deep_dive";
    public static final String COMPARISON = "comparison";
    public static final String TROUBLESHOOTING = "troubleshooting";
    public static final String NON_TECH = "non_tech";
    /** 续答意图：输入指代会话前文（"它/上面/那 X 呢"）或要求基于上文解释（"为什么不行"） */
    public static final String FOLLOWUP = "followup";

    private IntentKeys() {
    }
}
