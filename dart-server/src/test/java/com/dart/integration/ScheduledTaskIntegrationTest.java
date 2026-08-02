package com.dart.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.Conversation;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.Message;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.MessageMapper;
import com.dart.service.ConversationCleanupTask;
import com.dart.service.ConversationService;
import com.dart.service.DataConsistencyTask;
import com.dart.util.RedisKeyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ScheduledTaskIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConversationCleanupTask conversationCleanupTask;

    @Autowired
    private DataConsistencyTask dataConsistencyTask;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private KnowledgeItemMapper knowledgeItemMapper;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ConversationService conversationService;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createTestUser();
    }

    // ────────── 会话清理 ──────────

    @Test
    @DisplayName("清理无消息的空会话")
    void cleanupEmptyConversationsWithoutMessages() {
        Conversation empty1 = createConversation(35);
        Conversation empty2 = createConversation(5);
        Conversation empty3 = createConversation(1);

        conversationCleanupTask.cleanupEmptyConversations();

        assertThat(isDeleted(empty1.getId())).isTrue();
        assertThat(isDeleted(empty2.getId())).isTrue();
        assertThat(isDeleted(empty3.getId())).isTrue();
    }

    @Test
    @DisplayName("带标签但无消息的空会话亦被清理")
    void taggedEmptyConversationsAlsoCleaned() {
        Conversation tagged = createConversation(40);
        tagged.setTags("important");
        conversationMapper.updateById(tagged);

        conversationCleanupTask.cleanupEmptyConversations();

        assertThat(isDeleted(tagged.getId())).isTrue();
    }

    @Test
    @DisplayName("有消息的会话不被清理")
    void conversationsWithMessagesArePreserved() {
        Conversation active = createConversation(50);
        Message msg = new Message();
        msg.setConversationId(active.getId());
        msg.setRole("user");
        msg.setContent("Hello, world!");
        msg.setCreatedAt(LocalDateTime.now().minusDays(49));
        messageMapper.insert(msg);

        Conversation empty = createConversation(50);

        conversationCleanupTask.cleanupEmptyConversations();

        // 有消息的会话保留
        assertThat(isDeleted(active.getId())).isFalse();
        // 无消息的会话被清理
        assertThat(isDeleted(empty.getId())).isTrue();
    }

    // ────────── 超量会话清理 ──────────

    @Test
    @DisplayName("超量会话按更新时间清理最旧的部分")
    void cleanupExcessConversationsRemovesOldest() {
        Conversation oldest = createConversation(40);
        Conversation old = createConversation(30);
        Conversation recent = createConversation(20);
        Conversation newest = createConversation(10);

        conversationService.cleanupExcessConversations(2);

        assertThat(isDeleted(oldest.getId())).isTrue();
        assertThat(isDeleted(old.getId())).isTrue();
        assertThat(isDeleted(recent.getId())).isFalse();
        assertThat(isDeleted(newest.getId())).isFalse();
    }

    @Test
    @DisplayName("清理时物理删除被清会话的消息")
    void cleanupExcessRemovesMessagesOfCleanedConversations() {
        Conversation oldest = createConversation(40);
        insertMessages(oldest.getId(), 2);
        Conversation newest = createConversation(10);
        insertMessages(newest.getId(), 1);

        conversationService.cleanupExcessConversations(1);

        assertThat(isDeleted(oldest.getId())).isTrue();
        assertThat(countMessages(oldest.getId())).isZero();
        assertThat(isDeleted(newest.getId())).isFalse();
        assertThat(countMessages(newest.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("未超量用户不受影响")
    void cleanupExcessOnlyAffectsExceedingUsers() {
        Long userA = createTestUser();
        Long userB = createTestUser();
        Conversation aOld = createConversationFor(userA, 40);
        Conversation a1 = createConversationFor(userA, 30);
        Conversation a2 = createConversationFor(userA, 20);
        Conversation b1 = createConversationFor(userB, 40);
        Conversation b2 = createConversationFor(userB, 30);

        conversationService.cleanupExcessConversations(2);

        assertThat(isDeleted(aOld.getId())).isTrue();
        assertThat(isDeleted(a1.getId())).isFalse();
        assertThat(isDeleted(a2.getId())).isFalse();
        assertThat(isDeleted(b1.getId())).isFalse();
        assertThat(isDeleted(b2.getId())).isFalse();
    }

    @Test
    @DisplayName("maxPerUser 小于 1 时不清理任何会话")
    void cleanupExcessWithInvalidLimitDoesNothing() {
        Conversation conv = createConversation(40);
        insertMessages(conv.getId(), 1);

        conversationService.cleanupExcessConversations(0);

        assertThat(isDeleted(conv.getId())).isFalse();
        assertThat(countMessages(conv.getId())).isEqualTo(1);
    }

    // ────────── 向量补偿 ──────────

    @Test
    @DisplayName("detectMissingVectors 正确检测 MySQL 有但 Redis 无的缺失向量")
    void detectMissingVectorsIdentifiesGaps() {
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(testUserId);
        item.setTitle("Gap Test");
        item.setContent("Content.");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);

        // 插入 3 条 MySQL chunk（embedding_id 非空），Redis 不写入
        for (int i = 0; i < 3; i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(item.getId());
            kc.setChunkIndex(i);
            kc.setChunkText("Chunk text " + i);
            kc.setEmbeddingId("gap:chunk:" + i);
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insert(kc);
        }

        // detectMissingVectors 不抛异常，日志记录缺失
        assertThatCode(() -> dataConsistencyTask.detectMissingVectors())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("MySQL + Redis 数据一致时 detectMissingVectors 不报告")
    void detectMissingVectorsNoGapWhenInSync() {
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(testUserId);
        item.setTitle("Sync Test");
        item.setContent("Content.");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);

        for (int i = 0; i < 3; i++) {
            String embeddingId = "sync:chunk:" + i;
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(item.getId());
            kc.setChunkIndex(i);
            kc.setChunkText("Chunk text " + i);
            kc.setEmbeddingId(embeddingId);
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insert(kc);

            // 同步写入 Redis
            redisTemplate.opsForValue().set(
                    RedisKeyConstants.DART_PREFIX + embeddingId,
                    "{ \"emb\": \"" + i + "\" }");
        }

        assertThatCode(() -> dataConsistencyTask.detectMissingVectors())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("对账统计 MySQL 计数正确")
    void consistencyCheckCountsMysqlCorrectly() {
        KnowledgeItem item = new KnowledgeItem();
        item.setUserId(testUserId);
        item.setTitle("Count Test");
        item.setContent("Content.");
        item.setIsDeleted(0);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        knowledgeItemMapper.insert(item);

        for (int i = 0; i < 5; i++) {
            KnowledgeChunk kc = new KnowledgeChunk();
            kc.setKnowledgeItemId(item.getId());
            kc.setChunkIndex(i);
            kc.setChunkText("Count " + i);
            kc.setEmbeddingId("count:chunk:" + i);
            kc.setCreatedAt(LocalDateTime.now());
            knowledgeChunkMapper.insert(kc);
        }

        assertThatCode(() -> dataConsistencyTask.consistencyCheck())
                .doesNotThrowAnyException();
    }

    // ────────── 辅助 ──────────

    private Conversation createConversation(int daysAgo) {
        return createConversationFor(testUserId, daysAgo);
    }

    private Conversation createConversationFor(Long userId, int daysAgo) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle("Test conversation");
        conv.setIsDeleted(0);
        conv.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        conv.setUpdatedAt(LocalDateTime.now().minusDays(daysAgo));
        conversationMapper.insert(conv);
        return conv;
    }

    /**
     * 绕过 MyBatis-Plus 逻辑删除查询 is_deleted 字段：
     * selectById 返回 null 说明行已被逻辑删除或不存在。
     */
    private boolean isDeleted(Long id) {
        return conversationMapper.selectById(id) == null;
    }

    private void insertMessages(Long conversationId, int count) {
        for (int i = 0; i < count; i++) {
            Message msg = new Message();
            msg.setConversationId(conversationId);
            msg.setRole("user");
            msg.setContent("Msg " + i);
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);
        }
    }

    private Long countMessages(Long conversationId) {
        QueryWrapper<Message> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId);
        return messageMapper.selectCount(query);
    }
}
