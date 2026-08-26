package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dart.agent.curator.CuratedReport;
import com.dart.entity.Message;
import com.dart.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 会话记忆路径：续答回答（滚动摘要 + 最近报告）与追问建议生成。
 */
@Service
public class MemoryAnswerService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAnswerService.class);
    private static final int FOLLOW_UP_MAX_REPORT_CHARS = 3000;

    private final ChatModel chatModel;
    private final ConversationSummaryService conversationSummaryService;
    private final MessageMapper messageMapper;

    @Value("${agent.memory.recent-reports:2}")
    private int memoryRecentReports;

    @Value("${agent.memory.report-chars-per-round:3000}")
    private int memoryReportCharsPerRound;

    public MemoryAnswerService(ChatModel chatModel,
                               ConversationSummaryService conversationSummaryService,
                               MessageMapper messageMapper) {
        this.chatModel = chatModel;
        this.conversationSummaryService = conversationSummaryService;
        this.messageMapper = messageMapper;
    }

    /**
     * 基于会话记忆回答模糊输入：会话摘要 + 最近 N 轮 assistant 报告 → LLM 单次调用。
     * 失败返回 Optional.empty()，调用方回退正常搜索流程（fail-open）。
     */
    public Optional<String> generateMemoryAnswer(Long conversationId, Long userId, String query) {
        try {
            String summary = conversationSummaryService.getSummary(userId, conversationId);
            List<Message> recent = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .eq(Message::getRole, "assistant")
                            .orderByDesc(Message::getCreatedAt)
                            .last("LIMIT " + memoryRecentReports));
            StringBuilder reports = new StringBuilder();
            for (Message m : recent) {
                String content = m.getContent();
                if (content != null && content.length() > memoryReportCharsPerRound) {
                    content = content.substring(0, memoryReportCharsPerRound);
                }
                reports.append("<report>\n").append(QueryPreProcessor.escapeXml(content))
                        .append("\n</report>\n\n");
            }
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = """
                    你是会话记忆问答助手。用户在当前会话语境中提出了模糊/指代性问题，
                    请结合历史报告回答，不要假设存在未提供的信息。

                    历史会话摘要：<session_memory>
                    %s
                    </session_memory>

                    最近报告：
                    %s
                    用户问题：<user_query>%s</user_query>

                    要求：
                    - 直接回答问题；历史报告不足以回答时，如实说明缺少哪部分信息
                    - 引用必须来自历史报告，不得虚构来源
                    - 简洁、准确的中文回答
                    """.formatted(
                    summary == null || summary.isBlank() ? "（无）" : QueryPreProcessor.escapeXml(summary),
                    reports.isEmpty() ? "（无历史报告）" : reports,
                    sanitized);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return response.isBlank() ? Optional.empty() : Optional.of(response);
        } catch (Exception e) {
            log.warn("续答回答生成失败: query={}", query, e);
            return Optional.empty();
        }
    }

    /**
     * 基于知识图谱报告内容，由 LLM 生成 2-3 条自然语言追问建议。
     * 失败时返回 null，不影响主流程。
     */
    public String generateFollowUp(String query, String reportMarkdown,
                                   Long conversationId, Long userId) {
        try {
            String truncated = reportMarkdown.length() > FOLLOW_UP_MAX_REPORT_CHARS
                    ? reportMarkdown.substring(0, FOLLOW_UP_MAX_REPORT_CHARS)
                    : reportMarkdown;
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String summary = null;
            try {
                summary = conversationSummaryService.getSummary(userId, conversationId);
            } catch (Exception e) {
                log.warn("追问会话摘要读取失败，跳过注入: conversationId={}", conversationId, e);
            }
            String memorySection = (summary == null || summary.isBlank()) ? ""
                    : "历史会话摘要（可引用前几轮已讨论内容，追问应延续而非重复）：\n<session_memory>\n"
                            + QueryPreProcessor.escapeXml(summary) + "\n</session_memory>\n\n";
            String prompt = """
                    %s以下是一份知识学习报告（含 Mermaid 知识图谱和结构化章节），根据报告已覆盖的内容，提出2-3个读者可能感兴趣的追问方向。
                    追问应与知识图谱的分支主题相关但不重复已讲内容，以自然对话语气、中文写出。

                    用户查询：<user_query>%s</user_query>
                    报告摘要：
                    %s

                    严格按以下格式输出（每条追问以"- "开头，不要编号，不要加粗，不要空行）：
                    - 追问问题1
                    - 追问问题2
                    - 追问问题3
                    """.formatted(memorySection, sanitized, truncated);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText().trim();
            return response.isBlank() ? null : response;
        } catch (Exception e) {
            log.warn("追问生成失败，跳过", e);
            return null;
        }
    }

    public CuratedReport appendFollowUp(CuratedReport report, String followUp) {
        String markdown = report.markdown() + "\n\n---\n\n## 进一步探索\n\n" + followUp;
        return new CuratedReport(report.title(), markdown, report.sources(), report.generatedAt());
    }
}
