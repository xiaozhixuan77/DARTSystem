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

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public List<ConversationVO> listConversations(Long userId) {
        QueryWrapper<Conversation> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("is_deleted", 0)
                .orderByDesc("updated_at");
        List<Conversation> conversations = conversationMapper.selectList(query);

        return conversations.stream().map(conv -> {
            QueryWrapper<Message> countQuery = new QueryWrapper<>();
            countQuery.eq("conversation_id", conv.getId());
            long count = messageMapper.selectCount(countQuery);
            return new ConversationVO(
                    conv.getId(),
                    conv.getTitle(),
                    conv.getTags(),
                    (int) count,
                    conv.getUpdatedAt()
            );
        }).collect(Collectors.toList());
    }

    public ConversationVO createConversation(Long userId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle("新对话");
        conversation.setIsDeleted(0);
        conversationMapper.insertConversation(conversation);

        log.info("Conversation created: id={}, userId={}", conversation.getId(), userId);
        return new ConversationVO(
                conversation.getId(),
                conversation.getTitle(),
                null,
                0,
                conversation.getUpdatedAt()
        );
    }

    @Transactional
    public void updateConversation(Long userId, Long conversationId,
                                   ConversationUpsertRequest request) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        if (request.getTitle() != null) {
            conversation.setTitle(request.getTitle());
        }
        if (request.getTags() != null) {
            conversation.setTags(request.getTags());
        }

        conversationMapper.updateConversation(conversation);
        log.info("Conversation updated: id={}", conversationId);
    }

    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        conversationMapper.deleteById(conversationId);
        log.info("Conversation deleted: id={}", conversationId);
    }

    public List<MessageVO> getMessages(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        QueryWrapper<Message> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId)
                .orderByAsc("created_at");
        List<Message> messages = messageMapper.selectList(query);

        return messages.stream().map(msg -> new MessageVO(
                msg.getId(),
                msg.getRole(),
                msg.getContent(),
                msg.getMetadata(),
                msg.getCreatedAt()
        )).collect(Collectors.toList());
    }
}
