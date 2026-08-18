package com.dart.service;

import com.dart.entity.Conversation;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话滚动摘要（长记忆）：增量压缩更新 / 读取。
 * 摘要供追问、报告生成、前置调用注入，实现跨轮语义连贯（"接着讲讲它"可解析）。
 */
@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    private final ChatModel chatModel;
    private final ConversationMapper conversationMapper;

    @Value("${agent.memory.enabled:true}")
    private boolean memoryEnabled;

    @Value("${agent.memory.summary-max-chars:400}")
    private int summaryMaxChars;

    @Value("${agent.memory.summary-report-chars:4000}")
    private int summaryReportChars;

    public ConversationSummaryService(ChatModel chatModel, ConversationMapper conversationMapper) {
        this.chatModel = chatModel;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 读取会话摘要；无摘要返回空串。非本人会话拒绝（FORBIDDEN）。
     */
    public String getSummary(Long userId, Long conversationId) {
        Conversation conversation = requireOwned(userId, conversationId);
        return conversation.getSummary() == null ? "" : conversation.getSummary();
    }

    /**
     * 增量压缩更新会话摘要。内部异步执行（LLM 1-2s 不阻塞调用线程）；
     * fail-open：LLM 失败/空白响应保留旧摘要，不影响主流程。
     * 同步部分只做所有权校验与参数检查。
     */
    public void updateSummary(Long userId, Long conversationId, String query, String reportMarkdown) {
        if (!memoryEnabled) {
            return;
        }
        Conversation conversation = requireOwned(userId, conversationId);
        final String oldSummary = conversation.getSummary();
        Thread.startVirtualThread(() -> compressAndPersist(conversationId, oldSummary, query, reportMarkdown));
    }

    private void compressAndPersist(Long conversationId, String oldSummary,
                                    String query, String reportMarkdown) {
        try {
            String truncated = reportMarkdown == null ? ""
                    : (reportMarkdown.length() <= summaryReportChars
                        ? reportMarkdown : reportMarkdown.substring(0, summaryReportChars));
            if (truncated.isBlank()) {
                return;
            }
            String sanitized = query.replace("<", "&lt;").replace(">", "&gt;");
            String prompt = buildCompressPrompt(oldSummary, sanitized, truncated);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResults().getFirst().getOutput().getText();
            String summary = response == null ? "" : response.trim();
            if (summary.isBlank()) {
                log.warn("摘要生成空白，保留旧摘要: conversationId={}", conversationId);
                return;
            }
            if (summary.length() > summaryMaxChars) {
                // 超长截断存储而非丢弃：丢弃会导致后续追问指代失去上下文（歧义误判）
                summary = summary.substring(0, summaryMaxChars);
                log.info("摘要超长截断: conversationId={}, 保留 {} 字符", conversationId, summaryMaxChars);
            }
            Conversation update = new Conversation();
            update.setId(conversationId);
            update.setSummary(summary);
            conversationMapper.updateById(update);
            log.info("会话摘要已更新: conversationId={}", conversationId);
        } catch (Exception e) {
            log.warn("会话摘要更新失败，保留旧摘要: conversationId={}", conversationId, e);
        }
    }

    /**
     * 增量压缩 prompt：旧摘要 + 最新一轮 query + 报告 → 结构化新摘要。
     * 结构化字段（主题/已讨论/关键结论/关键资源/用户关注点）便于下游 LLM 直接利用。
     */
    String buildCompressPrompt(String oldSummary, String sanitizedQuery, String truncatedReport) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是会话记忆整理助手，负责把技术对话压缩为结构化滚动摘要。\n");
        if (oldSummary != null && !oldSummary.isBlank()) {
            sb.append("【旧摘要】（上次对话的压缩结果）：\n").append(oldSummary).append("\n\n");
        }
        sb.append("【最新一轮】用户问题：<user_query>").append(sanitizedQuery)
                .append("</user_query>\n\n报告正文：\n<report>\n")
                .append(truncatedReport)
                .append("\n</report>\n\n");
        sb.append("旧摘要不存在时直接根据最新一轮生成初始摘要。\n\n")
                .append("请把旧摘要与最新一轮内容合并，输出更新后的滚动摘要。要求：\n")
                .append("- 保持结构化：主题、已讨论、关键结论、关键资源、用户关注点 五个字段，每字段一行\n")
                .append("- 「已讨论」字段必须列出报告涉及的全部技术术语与专有名词"
                        + "（含小节标题中的术语，宁多勿少，逗号分隔）——后续对话会用这些术语解析指代，"
                        + "缺词会导致歧义误判\n")
                .append("- 新内容并入对应字段，重复内容不赘述；旧摘要中已被取代的结论可替换\n")
                .append("- 总字数必须严格控制在 ").append(summaryMaxChars)
                .append(" 字以内（超出部分会被系统截断丢失，请勿超长输出）\n")
                .append("- 只输出摘要正文，不要输出其他内容");
        return sb.toString();
    }

    private Conversation requireOwned(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return conversation;
    }
}
