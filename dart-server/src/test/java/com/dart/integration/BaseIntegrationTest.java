package com.dart.integration;

import com.dart.entity.Conversation;
import com.dart.entity.User;
import com.dart.mapper.ConversationMapper;
import com.dart.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAiConfig.class)
public abstract class BaseIntegrationTest {

    private static final List<String> TABLES = List.of(
            // 外键子表在前：knowledge_item_entity 依赖 knowledge_item/entity；
            // knowledge_entity 无外键可任意位置（放最前）
            "knowledge_entity", "knowledge_item_entity", "knowledge_relation",
            "knowledge_chunk", "knowledge_item", "search_trace", "message",
            "refresh_token", "conversation", "`user`"
    );

    @Autowired
    protected ChatModel chatModel;

    @Autowired
    protected EmbeddingModel embeddingModel;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanDatabase() {
        TABLES.forEach(table -> jdbcTemplate.execute("DELETE FROM " + table));
        var keys = redisTemplate.keys("dart:chunk:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        var cacheKeys = redisTemplate.keys("dart:report-cache:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }
    }

    protected Long createTestUser() {
        User user = new User();
        user.setUsername("test_" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash("no-auth");
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user.getId();
    }

    protected Long createTestConversation(Long userId) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle("测试会话");
        conv.setIsDeleted(0);
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv.getId();
    }

    /**
     * Mock EmbeddingModel 返回统一填充值的 1024 维向量（默认 0.01f）。
     */
    protected void mockEmbedding() {
        mockEmbedding(0.01f);
    }

    /**
     * Mock EmbeddingModel 返回指定填充值的 1024 维向量。
     * 同一向量多次调用产生余弦相似度 1.0（RAG 命中测试用）。
     */
    protected void mockEmbedding(float fillValue) {
        float[] vec = new float[1024];
        for (int i = 0; i < vec.length; i++) vec[i] = fillValue;
        var response = new EmbeddingResponse(
                List.of(new Embedding(vec, 0)),
                new EmbeddingResponseMetadata());
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(response);
    }
}
