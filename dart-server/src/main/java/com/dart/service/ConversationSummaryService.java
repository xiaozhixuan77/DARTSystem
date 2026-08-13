package com.dart.service;

import com.dart.entity.Conversation;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 会话滚动摘要（长记忆）：增量压缩更新 / 读取，供追问、报告生成、前置调用注入。
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

    /** 占位（feat 实现）：返回会话摘要，无摘要返回空串；非本人会话拒绝。 */
    public String getSummary(Long userId, Long conversationId) {
        return "";
    }

    /** 占位（feat 实现）：增量压缩更新摘要；LLM 失败保留旧摘要（fail-open）。 */
    public void updateSummary(Long userId, Long conversationId, String query, String reportMarkdown) {
    }
}
