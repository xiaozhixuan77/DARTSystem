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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;

    @InjectMocks
    private ConversationService conversationService;

    private static final Long USER_ID = 1L;
    private static final Long CONV_ID = 100L;

    private Conversation activeConv;

    @BeforeEach
    void setUp() {
        activeConv = new Conversation();
        activeConv.setId(CONV_ID);
        activeConv.setUserId(USER_ID);
        activeConv.setTitle("测试会话");
        activeConv.setTags("[\"Java\"]");
        activeConv.setIsDeleted(0);
        activeConv.setUpdatedAt(LocalDateTime.now());
    }

    // === listConversations ===

    @Test
    void listConversations_returnsList() {
        when(conversationMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(activeConv));
        when(messageMapper.selectMaps(any(QueryWrapper.class)))
                .thenReturn(List.of(Map.of("conversation_id", CONV_ID, "cnt", 3L)));

        List<ConversationVO> result = conversationService.listConversations(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(CONV_ID);
        assertThat(result.get(0).getTitle()).isEqualTo("测试会话");
        assertThat(result.get(0).getMessageCount()).isEqualTo(3);
    }

    // === createConversation ===

    @Test
    void createConversation_success() {
        doAnswer(inv -> {
            Conversation c = inv.getArgument(0, Conversation.class);
            c.setId(200L);
            return 1;
        }).when(conversationMapper).insert(any(Conversation.class));

        ConversationUpsertRequest req = new ConversationUpsertRequest();
        req.setTitle("测试会话");
        ConversationVO result = conversationService.createConversation(USER_ID, req);

        assertThat(result.getId()).isEqualTo(200L);
        assertThat(result.getTitle()).isEqualTo("测试会话");
        assertThat(result.getMessageCount()).isZero();
        verify(conversationMapper).insert(any(Conversation.class));
    }

    @Test
    void createConversation_nullRequest_usesDefaultTitle() {
        doAnswer(inv -> {
            Conversation c = inv.getArgument(0, Conversation.class);
            c.setId(201L);
            return 1;
        }).when(conversationMapper).insert(any(Conversation.class));

        // 前端创建会话可不传 body
        ConversationVO result = conversationService.createConversation(USER_ID, null);

        assertThat(result.getId()).isEqualTo(201L);
        assertThat(result.getTitle()).isEqualTo("新对话");
        verify(conversationMapper).insert(any(Conversation.class));
    }

    // === updateConversation ===

    @Test
    void updateConversation_success() {
        ConversationUpsertRequest req = new ConversationUpsertRequest();
        req.setTitle("更新标题");
        req.setTags("[\"Spring\"]");

        when(conversationMapper.selectById(CONV_ID)).thenReturn(activeConv);

        conversationService.updateConversation(USER_ID, CONV_ID, req);

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("更新标题");
        assertThat(captor.getValue().getTags()).isEqualTo("[\"Spring\"]");
    }

    @Test
    void updateConversation_notFound_throwsException() {
        when(conversationMapper.selectById(999L)).thenReturn(null);

        ConversationUpsertRequest req = new ConversationUpsertRequest();
        req.setTitle("新标题");

        assertThatThrownBy(() -> conversationService.updateConversation(USER_ID, 999L, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void updateConversation_wrongUser_throwsException() {
        Conversation otherConv = new Conversation();
        otherConv.setId(200L);
        otherConv.setUserId(999L);
        when(conversationMapper.selectById(200L)).thenReturn(otherConv);

        ConversationUpsertRequest req = new ConversationUpsertRequest();
        req.setTitle("新标题");

        assertThatThrownBy(() -> conversationService.updateConversation(USER_ID, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // === deleteConversation ===

    @Test
    void deleteConversation_success() {
        when(conversationMapper.selectById(CONV_ID)).thenReturn(activeConv);

        conversationService.deleteConversation(USER_ID, CONV_ID);

        verify(conversationMapper).deleteById(CONV_ID);

        ArgumentCaptor<QueryWrapper> deleteCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getSqlSegment()).contains("conversation_id");
    }

    @Test
    void deleteConversation_notFound_throwsException() {
        when(conversationMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> conversationService.deleteConversation(USER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
        verify(messageMapper, never()).delete(any());
    }

    // === getMessages ===

    @Test
    void getMessages_returnsMessages() {
        Message msg = new Message();
        msg.setId(1L);
        msg.setConversationId(CONV_ID);
        msg.setRole("user");
        msg.setContent("测试问题");
        msg.setCreatedAt(LocalDateTime.now());

        when(conversationMapper.selectById(CONV_ID)).thenReturn(activeConv);
        when(messageMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(msg));

        List<MessageVO> result = conversationService.getMessages(USER_ID, CONV_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRole()).isEqualTo("user");
        assertThat(result.get(0).getContent()).isEqualTo("测试问题");
    }

    @Test
    void getMessages_conversationNotFound_throwsException() {
        when(conversationMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> conversationService.getMessages(USER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONVERSATION_NOT_FOUND);
    }

    @Test
    void deleteConversation_wrongUser_throwsException() {
        Conversation otherConv = new Conversation();
        otherConv.setId(300L);
        otherConv.setUserId(999L);
        when(conversationMapper.selectById(300L)).thenReturn(otherConv);

        assertThatThrownBy(() -> conversationService.deleteConversation(USER_ID, 300L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(messageMapper, never()).delete(any());
    }

    @Test
    void getMessages_wrongUser_throwsException() {
        Conversation otherConv = new Conversation();
        otherConv.setId(400L);
        otherConv.setUserId(999L);
        when(conversationMapper.selectById(400L)).thenReturn(otherConv);

        assertThatThrownBy(() -> conversationService.getMessages(USER_ID, 400L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // === cleanupExcessConversations ===

    @Test
    void cleanupExcessConversations_removesOldestBeyondLimit() {
        when(conversationMapper.selectMaps(any(QueryWrapper.class)))
                .thenReturn(List.of(Map.of("user_id", 1L, "cnt", 3L)));

        Conversation oldest = new Conversation();
        oldest.setId(1L);
        oldest.setUserId(1L);
        oldest.setIsDeleted(0);
        when(conversationMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(oldest));

        conversationService.cleanupExcessConversations(2);

        verify(conversationMapper).deleteById(1L);
        verify(messageMapper).delete(any(QueryWrapper.class));
    }

    @Test
    void cleanupExcessConversations_noUsersExceedLimit_noop() {
        when(conversationMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of());

        conversationService.cleanupExcessConversations(2);

        verify(conversationMapper, never()).deleteById((Long) any());
        verify(messageMapper, never()).delete(any());
    }

    @Test
    void cleanupExcessConversations_userWithinLimit_noop() {
        when(conversationMapper.selectMaps(any(QueryWrapper.class)))
                .thenReturn(List.of(Map.of("user_id", 1L, "cnt", 2L)));

        conversationService.cleanupExcessConversations(2);

        verify(conversationMapper, never()).deleteById((Long) any());
        verify(messageMapper, never()).delete(any());
    }

    @Test
    void cleanupExcessConversations_invalidLimit_skips() {
        conversationService.cleanupExcessConversations(0);

        verifyNoInteractions(conversationMapper, messageMapper);
    }

    @Test
    void cleanupExcessConversations_deleteMessagesPerConversation() {
        when(conversationMapper.selectMaps(any(QueryWrapper.class)))
                .thenReturn(List.of(
                        Map.of("user_id", 1L, "cnt", 3L),
                        Map.of("user_id", 2L, "cnt", 3L)));

        Conversation old1 = new Conversation();
        old1.setId(11L);
        old1.setUserId(1L);
        old1.setIsDeleted(0);
        Conversation old2 = new Conversation();
        old2.setId(22L);
        old2.setUserId(2L);
        old2.setIsDeleted(0);
        when(conversationMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(old1), List.of(old2));

        conversationService.cleanupExcessConversations(2);

        verify(conversationMapper).deleteById(11L);
        verify(conversationMapper).deleteById(22L);
        verify(messageMapper, times(2)).delete(any(QueryWrapper.class));
    }
}
