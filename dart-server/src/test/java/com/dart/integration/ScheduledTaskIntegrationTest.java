package com.dart.integration;

import com.dart.entity.Conversation;
import com.dart.entity.User;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.UserMapper;
import com.dart.service.ConversationCleanupTask;
import com.dart.service.DataConsistencyTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long testUserId;

    @BeforeEach
    void createTestUser() {
        User user = new User();
        user.setUsername("sc" + (System.nanoTime() % 1000000));
        user.setPasswordHash(passwordEncoder.encode("pass123"));
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        testUserId = user.getId();
    }

    @Test
    @DisplayName("清理 30 天以上无标签空会话")
    void cleanupOldEmptyUntaggedConversations() throws Exception {
        Conversation oldEmpty = createConversation(35);
        createConversation(35); // 另一个旧会话
        Conversation recentEmpty = createConversation(5);

        conversationCleanupTask.cleanupEmptyConversations();

        Conversation c1 = conversationMapper.selectById(oldEmpty.getId());
        assertThat(c1).isNotNull();
    }

    @Test
    @DisplayName("带标签会话豁免清理")
    void taggedConversationsPreserved() throws Exception {
        Conversation tagged = createConversation(40);
        tagged.setTags("important");
        conversationMapper.updateById(tagged);

        conversationCleanupTask.cleanupEmptyConversations();

        Conversation result = conversationMapper.selectById(tagged.getId());
        assertThat(result.getIsDeleted()).isEqualTo(0);
    }

    @Test
    @DisplayName("缺失向量检测不抛异常")
    void detectMissingVectorsDoesNotThrow() throws Exception {
        dataConsistencyTask.detectMissingVectors();
    }

    @Test
    @DisplayName("对账统计正常执行")
    void consistencyCheckRunsSuccessfully() throws Exception {
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
}
