package com.dart.evaluation;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge：对 AI 回答做五维评分（相关性/准确性/完整性/清晰度/引源），1-5 分。
 * 解析失败降级全部 3 分（不阻断评估流程）。
 */
public class LlmJudge {

    public static final List<String> DIMENSIONS =
            List.of("relevance", "accuracy", "completeness", "clarity", "sourcing");

    private final ChatModel chatModel;

    public LlmJudge(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public Map<String, Integer> score(String query, String answer, List<String> keyConcepts) {
        String sanitized = query.replace("<", "&lt;");
        String truncated = answer == null ? "" : (answer.length() > 4000 ? answer.substring(0, 4000) : answer);
        String prompt = """
                你是一个技术内容评审专家。对以下 AI 回答进行五维度评分（1-5 分整数）。
                评分要严格、客观，宁低勿高。

                【用户查询】
                %s

                【AI回答】
                %s

                【参考关键知识点】
                %s

                评分维度：
                1. relevance (相关性)：回答是否紧扣查询主题
                2. accuracy (准确性)：技术内容是否正确、无误导
                3. completeness (完整性)：是否覆盖关键知识点
                4. clarity (清晰度)：结构是否清晰、可读性如何
                5. sourcing (引源)：是否提供来源/引用支撑

                只输出 JSON，不要输出任何其他内容：
                {"relevance":5,"accuracy":4,"completeness":4,"clarity":5,"sourcing":4}
                """.formatted(sanitized, truncated,
                keyConcepts == null ? "" : String.join("、", keyConcepts));
        try {
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText();
            Map<String, Integer> parsed = parse(response);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception e) {
            // 降级
        }
        return fallbackScores();
    }

    /**
     * 解析五维 JSON；字段缺失/越界/畸形返回 null（调用方降级）。
     */
    public static Map<String, Integer> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + dim + "\"\\s*:\\s*(\\d+)").matcher(json);
            if (!m.find()) {
                return null;
            }
            int value;
            try {
                value = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 1 || value > 5) {
                return null;
            }
            scores.put(dim, value);
        }
        return scores;
    }

    public static Map<String, Integer> fallbackScores() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            scores.put(dim, 3);
        }
        return scores;
    }
}
