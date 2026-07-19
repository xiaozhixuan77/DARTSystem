package com.dart.agent.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 语义匹配评分：调用 LLM 评估查询与页面内容的语义匹配度（1-5）。
 * LLM 异常或无法解析时降级返回 3（默认值），不中断评分流程。
 */
public class MatchScorer {

    private static final Logger log = LoggerFactory.getLogger(MatchScorer.class);

    private final ChatModel chatModel;

    public MatchScorer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public int evaluate(String query, String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }
        String promptText = buildPrompt(query, content);
        try {
            String response = chatModel.call(new Prompt(new UserMessage(promptText)))
                    .getResults()
                    .getFirst()
                    .getOutput()
                    .getText()
                    .trim();
            return parseScore(response);
        } catch (Exception e) {
            log.warn("LLM 语义匹配评分失败，降级使用默认值 3", e);
            return 3;
        }
    }

    private String buildPrompt(String query, String content) {
        return """
                你是一个技术资源评估专家。请评估以下页面内容与用户查询的语义匹配程度。

                评分标准（1-5分）：
                - 5：完全匹配，内容直接回答查询问题
                - 4：高度相关，内容覆盖查询的大部分要点
                - 3：部分相关，内容涉及查询相关领域但不够聚焦
                - 2：弱相关，内容仅涉及相关概念
                - 1：不相关，内容与查询基本无关

                用户查询：%s

                页面内容（摘要）：%s

                请仅输出一个 1-5 的整数分数，不要输出其他内容。""".formatted(query, truncate(content));
    }

    private String truncate(String content) {
        if (content.length() <= 1000) {
            return content;
        }
        return content.substring(0, 1000) + "...";
    }

    private int parseScore(String text) {
        try {
            int score = Integer.parseInt(text);
            if (score < 1) return 1;
            if (score > 5) return 5;
            return score;
        } catch (NumberFormatException e) {
            log.warn("LLM 返回无法解析的分数: '{}'，降级使用默认值 3", text);
            return 3;
        }
    }
}
