package com.dart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识实体抽取：报告沉淀后单次 LLM 调用提取主题（白名单 topic）与核心技术概念（concept）。
 * 失败/解析失败返回空列表，不影响沉淀主流程（fail-open）。
 */
@Slf4j
@Service
public class EntityExtractionService {

    /** 受控主题白名单：LLM 只允许输出列表内主题；无法归类时不输出主题（不产生兜底实体）。 */
    public static final Set<String> TOPICS = Set.of(
            "Java", "Spring", "数据库", "缓存", "消息队列", "微服务/分布式", "前端",
            "容器/云原生", "中间件", "大数据", "AI 大模型", "网络安全",
            "操作系统", "网络", "DevOps 工具链", "性能优化", "算法与数据结构");

    /** 概念每篇报告上限：减少实体产生量，保留 LLM 输出顺序前 N 个。 */
    private static final int MAX_CONCEPTS_PER_REPORT = 2;

    /** 主题每篇报告硬上限：prompt 要求 1-2，LLM 超出时强制截断。 */
    private static final int MAX_TOPICS_PER_REPORT = 2;

    /** 描述性壳词（精确匹配）：区分度不足，不作为实体。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "特点", "简介", "概述", "用法", "教程", "入门", "写法",
            "基础语法", "最佳实践", "优缺点", "原理", "使用",
            "详解", "介绍", "基础", "实战", "实践", "指南", "笔记",
            "总结", "对比", "区别", "浅析", "本质");

    private static final int MAX_CONCEPT_LEN = 10;
    private static final int MAX_REPORT_CHARS = 3000;

    public record ExtractedEntities(List<String> topics, List<String> concepts) {
        public static ExtractedEntities empty() {
            return new ExtractedEntities(List.of(), List.of());
        }
        public boolean isEmpty() {
            return topics.isEmpty() && concepts.isEmpty();
        }
    }

    private final ChatModel chatModel;

    public EntityExtractionService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 抽取主题与概念；LLM 失败/输出畸形 → 空列表（调用方跳过实体关联）。
     */
    public ExtractedEntities extract(String title, String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return ExtractedEntities.empty();
        }
        try {
            String sanitized = markdown.length() > MAX_REPORT_CHARS
                    ? markdown.substring(0, MAX_REPORT_CHARS) : markdown;
            String prompt = buildPrompt(title, sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText();
            return parse(response, sanitized);
        } catch (Exception e) {
            log.warn("实体抽取 LLM 调用失败，跳过实体关联: title={}", title, e);
            return ExtractedEntities.empty();
        }
    }

    private static String buildPrompt(String title, String sanitized) {
        String topicList = String.join("、", TOPICS);
        return """
                从以下学习报告中提取技术主题和核心技术概念。
                严格只输出 JSON，不要输出任何其他内容：
                {"topics": ["主题1", "主题2"], "concepts": ["概念1", "概念2", ...]}

                规则：
                - topics：从以下列表中选择 1-2 个最贴切的主题（严格使用列表内的名称）：
                  %s
                  无法归类时不输出主题（不要使用列表外的名称）
                - concepts：只提取 1-2 个报告中出现过的、最能精准定位该报告的核心技术专有名词
                  （每个不超过 10 字；好例：Redis、LRU 缓存；坏例：特点、简介、基础、详解、xx 原理）；
                  宁缺毋滥——没有高价值概念时可以不输出；不要包含 topics 中已选的主题名；
                  不得抽取描述性通用词；不得编造报告中不存在的词

                <title>
                %s
                </title>
                <report>
                %s
                </report>
                """.formatted(topicList,
                title == null ? "" : title.replace("<", "&lt;").replace(">", "&gt;"),
                sanitized.replace("<", "&lt;").replace(">", "&gt;"));
    }

    /**
     * 正则解析 JSON（容错：畸形/缺失字段丢弃该条，不抛异常）。
     * 概念走四重过滤：白名单归并 → 长度 → 壳词 → 报告正文来源校验（防 LLM 编造）。
     */
    private static ExtractedEntities parse(String json, String reportText) {
        if (json == null || json.isBlank()) {
            return ExtractedEntities.empty();
        }
        String lowerReport = reportText == null ? "" : reportText.toLowerCase();
        List<String> topics = new ArrayList<>();
        Matcher t = Pattern.compile("\"topics\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (t.find()) {
            for (String name : splitArray(t.group(1))) {
                if (TOPICS.contains(name) && !topics.contains(name)) {
                    topics.add(name);
                }
            }
        }
        if (topics.size() > MAX_TOPICS_PER_REPORT) {
            topics = new ArrayList<>(topics.subList(0, MAX_TOPICS_PER_REPORT));
        }
        List<String> concepts = new ArrayList<>();
        Matcher c = Pattern.compile("\"concepts\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (c.find()) {
            for (String name : splitArray(c.group(1))) {
                // 与白名单同名的概念归并到 topic 侧，不重复生成 concept 实体
                if (TOPICS.contains(name) || name.length() > MAX_CONCEPT_LEN
                        || STOP_WORDS.contains(name)
                        || !lowerReport.contains(name.toLowerCase())) {
                    continue;
                }
                if (!concepts.contains(name)) {
                    concepts.add(name);
                    if (concepts.size() >= MAX_CONCEPTS_PER_REPORT) {
                        break;
                    }
                }
            }
        }
        return new ExtractedEntities(List.copyOf(topics), List.copyOf(concepts));
    }

    private static List<String> splitArray(String arrayContent) {
        List<String> names = new ArrayList<>();
        Matcher item = Pattern.compile("\"([^\"]*)\"").matcher(arrayContent);
        while (item.find()) {
            String v = item.group(1).trim();
            if (!v.isBlank()) {
                names.add(v);
            }
        }
        return names;
    }
}
