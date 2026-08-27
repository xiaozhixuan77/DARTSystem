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

    /** 受控主题白名单：LLM 只允许输出列表内主题；无法归类输出"其他"。 */
    public static final Set<String> TOPICS = Set.of(
            "Java", "Spring", "数据库", "缓存", "消息队列", "微服务/分布式", "前端",
            "容器/云原生", "中间件", "大数据", "AI 大模型", "网络安全",
            "操作系统", "网络", "DevOps 工具链", "性能优化", "算法与数据结构", "其他");

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
            return parse(response);
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
                  无法归类时选择"其他"
                - concepts：提取 2-5 个报告中的核心技术概念（名词短语，每个不超过 10 字，
                  如 Redis、缓存淘汰策略、LRU）；不要包含 topics 中已选的主题名

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
     */
    private static ExtractedEntities parse(String json) {
        if (json == null || json.isBlank()) {
            return ExtractedEntities.empty();
        }
        List<String> topics = new ArrayList<>();
        Matcher t = Pattern.compile("\"topics\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (t.find()) {
            for (String name : splitArray(t.group(1))) {
                if (TOPICS.contains(name) && !topics.contains(name)) {
                    topics.add(name);
                }
            }
        }
        List<String> concepts = new ArrayList<>();
        Matcher c = Pattern.compile("\"concepts\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (c.find()) {
            for (String name : splitArray(c.group(1))) {
                // 与白名单同名的概念归并到 topic 侧，不重复生成 concept 实体
                if (TOPICS.contains(name) || name.length() > MAX_CONCEPT_LEN) {
                    continue;
                }
                if (!concepts.contains(name)) {
                    concepts.add(name);
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
