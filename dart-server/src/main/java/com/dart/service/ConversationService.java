package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.dto.request.ConversationUpsertRequest;
import com.dart.dto.response.ConversationVO;
import com.dart.dto.response.MessageVO;
import com.dart.entity.Conversation;
import com.dart.entity.Message;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public List<ConversationVO> listConversations(Long userId) {
        validateUserId(userId);

        QueryWrapper<Conversation> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("updated_at");
        List<Conversation> conversations = conversationMapper.selectList(query);

        Map<Long, Long> countMap = batchCountMessages(conversations);

        return conversations.stream().map(conv -> ConversationVO.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .tags(conv.getTags())
                .messageCount(countMap.getOrDefault(conv.getId(), 0L).intValue())
                .updatedAt(conv.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }

    private Map<Long, Long> batchCountMessages(List<Conversation> conversations) {
        if (conversations.isEmpty()) {
            return Map.of();
        }
        List<Long> convIds = conversations.stream().map(Conversation::getId).toList();
        Map<Long, Long> countMap = new HashMap<>();
        QueryWrapper<Message> countQuery = new QueryWrapper<>();
        countQuery.select("conversation_id", "count(1) as cnt")
                .in("conversation_id", convIds)
                .groupBy("conversation_id");
        for (Map<String, Object> row : messageMapper.selectMaps(countQuery)) {
            Long convId = ((Number) row.get("conversation_id")).longValue();
            Long cnt = ((Number) row.get("cnt")).longValue();
            countMap.put(convId, cnt);
        }
        return countMap;
    }

    public ConversationVO createConversation(Long userId, ConversationUpsertRequest request) {
        validateUserId(userId);

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle() : "新对话");
        conversation.setTags(request.getTags());
        conversation.setIsDeleted(0);
        conversationMapper.insert(conversation);

        log.info("Conversation created: id={}, userId={}", conversation.getId(), userId);
        return ConversationVO.builder()
                .id(conversation.getId())
                .title(conversation.getTitle())
                .tags(conversation.getTags())
                .messageCount(0)
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    public ConversationVO getConversation(Long userId, Long conversationId) {
        validateUserId(userId);
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return ConversationVO.builder()
                .id(conversation.getId())
                .title(conversation.getTitle())
                .tags(conversation.getTags())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    @Transactional
    public void updateConversation(Long userId, Long conversationId,
                                   ConversationUpsertRequest request) {
        validateUserId(userId);
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (request.getTitle() != null) {
            conversation.setTitle(request.getTitle());
        }
        if (request.getTags() != null) {
            conversation.setTags(request.getTags());
        }

        conversationMapper.updateById(conversation);
        log.info("Conversation updated: id={}", conversationId);
    }

    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        validateUserId(userId);
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        conversation.setIsDeleted(1);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        log.info("Conversation soft-deleted: id={}", conversationId);
    }

    public List<MessageVO> getMessages(Long userId, Long conversationId) {
        validateUserId(userId);
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        QueryWrapper<Message> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId)
                .orderByAsc("created_at");
        List<Message> messages = messageMapper.selectList(query);

        return messages.stream().map(msg -> MessageVO.builder()
                .id(msg.getId())
                .role(msg.getRole())
                .content(msg.getContent())
                .metadata(msg.getMetadata())
                .createdAt(msg.getCreatedAt())
                .build()).collect(Collectors.toList());
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
