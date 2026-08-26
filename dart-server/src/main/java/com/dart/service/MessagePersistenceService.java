package com.dart.service;

import com.dart.entity.Message;
import com.dart.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 助手消息持久化：草稿（流式期间快照）→ 完成写回 / 整条插入，均独立事务立即提交。
 */
@Service
public class MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceService.class);

    private final MessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;

    public MessagePersistenceService(MessageMapper messageMapper,
                                     TransactionTemplate transactionTemplate) {
        this.messageMapper = messageMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 草稿消息：curator 流式生成前先落空消息（独立事务立即提交 + draft 标记），
     * 流式中断后前端可拉取到已生成部分；完整报告生成后由 completeAssistantMessage 写回。
     */
    public Message persistAssistantDraft(Long conversationId) {
        try {
            Message draft = new Message();
            draft.setConversationId(conversationId);
            draft.setRole("assistant");
            draft.setContent("");
            draft.setMetadata("{\"draft\":true}");
            draft.setCreatedAt(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(status -> messageMapper.insert(draft));
            return draft;
        } catch (Exception e) {
            log.error("创建助手消息草稿失败，回退完整插入", e);
            return null;
        }
    }

    /**
     * 草稿增量更新：流式生成过程中的快照（独立事务立即提交，仅更新内容保留 draft 标记）。
     */
    public void updateAssistantDraft(Long messageId, String content) {
        if (messageId == null) {
            return;
        }
        try {
            Message draft = new Message();
            draft.setId(messageId);
            draft.setContent(content);
            transactionTemplate.executeWithoutResult(status -> messageMapper.updateById(draft));
        } catch (Exception e) {
            log.warn("助手消息草稿快照更新失败: messageId={}", messageId, e);
        }
    }

    /**
     * 草稿完成后写回完整内容（同一消息，清 draft 标记）；草稿不存在（创建失败）
     * 时回退原完整插入路径。updateById 在主事务内，随事务提交对前端可见。
     */
    public Message completeAssistantMessage(Message draft, Long conversationId,
                                            ChatService.SearchResult result) {
        if (draft == null || draft.getId() == null) {
            return persistAssistantMessage(conversationId, result);
        }
        try {
            applyResultToMessage(draft, result);
            draft.setCreatedAt(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(status -> messageMapper.updateById(draft));
            return draft;
        } catch (Exception e) {
            log.error("助手消息草稿完善失败", e);
            return null;
        }
    }

    public Message persistAssistantMessage(Long conversationId, ChatService.SearchResult result) {
        try {
            Message assistantMsg = new Message();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole("assistant");
            applyResultToMessage(assistantMsg, result);
            assistantMsg.setCreatedAt(LocalDateTime.now());
            transactionTemplate.executeWithoutResult(status -> messageMapper.insert(assistantMsg));
            return assistantMsg;
        } catch (Exception e) {
            log.error("保存助手消息失败", e);
            return null;
        }
    }

    private void applyResultToMessage(Message msg, ChatService.SearchResult result) {
        if (result.isRagHit()) {
            msg.setContent(result.ragAnswer().answer());
            msg.setMetadata("{\"type\":\"rag\"," +
                    "\"sources\":" + toJsonArray(result.ragAnswer().sourceUrls()) + "}");
        } else if (result.isFollowUpHit()) {
            msg.setContent(result.followUpAnswer());
            msg.setMetadata("{\"type\":\"followup\"}");
        } else if (result.curatedReport() != null) {
            msg.setContent(result.curatedReport().markdown());
            msg.setMetadata("{\"type\":\"search\"," +
                    "\"sources\":" + toJsonArray(result.curatedReport().sources()) +
                    ",\"resultCount\":" + result.searchResults().size() + "}");
        }
    }

    private static String toJsonArray(List<String> urls) {
        if (urls == null || urls.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(",");
            // 先转义反斜杠再转义引号，防 URL 含 \ 时产出非法 JSON
            sb.append("\"").append(urls.get(i)
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
