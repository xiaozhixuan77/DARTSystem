package com.dart.integration;

import com.dart.entity.User;
import com.dart.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAiConfig.class)
public abstract class BaseIntegrationTest {

    private static final List<String> TABLES = List.of(
            "knowledge_chunk", "knowledge_item", "message",
            "refresh_token", "conversation", "users"
    );

    @Autowired
    protected ChatModel chatModel;

    @Autowired
    protected EmbeddingModel embeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        TABLES.forEach(table -> jdbcTemplate.execute("DELETE FROM " + table));
    }

    /**
     * 在数据库中创建一个活跃用户，返回其 ID。
     * 需要 FK 关联的测试可在 {@link BeforeEach} 中调用此方法。
     */
    protected Long createTestUser() {
        User user = new User();
        user.setUsername("test_" + (System.nanoTime() % 1000000));
        user.setPasswordHash(passwordEncoder.encode("pass123"));
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user.getId();
    }
}
