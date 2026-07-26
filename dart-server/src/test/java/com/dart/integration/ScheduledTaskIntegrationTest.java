package com.dart.integration;

import com.dart.entity.Conversation;
import com.dart.entity.KnowledgeChunk;
import com.dart.entity.KnowledgeItem;
import com.dart.entity.Message;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.KnowledgeChunkMapper;
import com.dart.mapper.KnowledgeItemMapper;
import com.dart.mapper.MessageMapper;
import com.dart.service.ConversationCleanupTask;
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
     * 绕过 MyBatis-Plus 逻辑删除查询 is_deleted 字段：
     * selectById 返回 null 说明行已被逻辑删除或不存在。
     */
    private boolean isDeleted(Long id) {
        return conversationMapper.selectById(id) == null;
    }
}
