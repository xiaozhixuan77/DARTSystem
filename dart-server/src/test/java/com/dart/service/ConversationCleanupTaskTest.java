package com.dart.service;

import com.dart.mapper.ConversationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConversationCleanupTaskTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private ConversationService conversationService;

    @Test
    void cleanupExcessConversations_delegatesToService() {
        ConversationCleanupTask task = new ConversationCleanupTask(conversationMapper, conversationService, 2);

        task.cleanupExcessConversations();

        verify(conversationService).cleanupExcessConversations(2);
    }

    @Test
    void cleanupExcessConversations_serviceThrows_notPropagated() {
        ConversationCleanupTask task = new ConversationCleanupTask(conversationMapper, conversationService, 2);
        doThrow(new RuntimeException("boom")).when(conversationService).cleanupExcessConversations(2);

        assertThatCode(task::cleanupExcessConversations).doesNotThrowAnyException();
    }
}
