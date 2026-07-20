package com.dart.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.dart.entity.Conversation;
import com.dart.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@ActiveProfiles("test")
class ConversationMapperTest {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private UserMapper userMapper;

    private Long userId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("test_user_crud");
        user.setPasswordHash("$2a$10$hashed");
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        userMapper.insert(user);
        userId = user.getId();
    }

    @Test
    void insert_and_selectById() {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle("测试会话");
        conv.setIsDeleted(0);
        conversationMapper.insert(conv);

        assertThat(conv.getId()).isNotNull();

        Conversation found = conversationMapper.selectById(conv.getId());
        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("测试会话");
        assertThat(found.getUserId()).isEqualTo(userId);
    }

    @Test
    void selectList_filtersDeleted() {
        Conversation active = new Conversation();
        active.setUserId(userId);
        active.setTitle("活跃会话");
        active.setIsDeleted(0);
        conversationMapper.insert(active);

        Conversation deleted = new Conversation();
        deleted.setUserId(userId);
        deleted.setTitle("已删除会话");
        deleted.setIsDeleted(1);
        conversationMapper.insert(deleted);

        QueryWrapper<Conversation> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("is_deleted", 0);
        List<Conversation> list = conversationMapper.selectList(query);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTitle()).isEqualTo("活跃会话");
    }
}
