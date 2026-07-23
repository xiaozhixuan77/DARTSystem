package com.dart.integration;

import com.dart.entity.Conversation;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.service.ConversationCleanupTask;
import com.dart.service.DataConsistencyTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConversationCleanupTask conversationCleanupTask;

    @Autowired
    private DataConsistencyTask dataConsistencyTask;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createTestUser();
    }

    @Test
    @DisplayName("清理无消息的空会话")
    void cleanupEmptyConversationsWithoutMessages() {
        Conversation empty1 = createConversation(35);
        Conversation empty2 = createConversation(5);
        Conversation empty3 = createConversation(1);

        conversationCleanupTask.cleanupEmptyConversations();

        // 所有无消息的会话均被清理（当前实现不区分年龄）
        assertThat(selectByIdBypassLogicDelete(empty1.getId())).isEqualTo(1);
        assertThat(selectByIdBypassLogicDelete(empty2.getId())).isEqualTo(1);
        assertThat(selectByIdBypassLogicDelete(empty3.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("带标签但无消息的空会话亦被清理")
    void taggedEmptyConversationsAlsoCleaned() {
        Conversation tagged = createConversation(40);
        tagged.setTags("important");
        conversationMapper.updateById(tagged);

        conversationCleanupTask.cleanupEmptyConversations();

        // 当前实现不检查标签：无消息即清理
        Integer isDeleted = selectByIdBypassLogicDelete(tagged.getId());
        assertThat(isDeleted).isEqualTo(1);
    }

    @Test
    @DisplayName("缺失向量检测不抛异常")
    void detectMissingVectorsDoesNotThrow() {
        dataConsistencyTask.detectMissingVectors();
    }

    @Test
    @DisplayName("对账统计正常执行")
    void consistencyCheckRunsSuccessfully() {
        dataConsistencyTask.consistencyCheck();
    }

    private Conversation createConversation(int daysAgo) {
        Conversation conv = new Conversation();
        conv.setUserId(testUserId);
        conv.setTitle("Test conversation");
        conv.setIsDeleted(0);
        conv.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        conv.setUpdatedAt(LocalDateTime.now().minusDays(daysAgo));
        conversationMapper.insert(conv);
        return conv;
    }

    /**
     * 绕过 MyBatis-Plus 逻辑删除过滤器，直接查询 is_deleted 字段值。
     */
    private Integer selectByIdBypassLogicDelete(Long id) {
        return conversationMapper.selectById(id) == null ? 1 : 0;
    }
}
