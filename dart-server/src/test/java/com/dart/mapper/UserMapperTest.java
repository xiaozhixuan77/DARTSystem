package com.dart.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.dart.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@ActiveProfiles("h2test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "classpath:schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void insert_and_selectByUsername() {
        User user = new User();
        user.setUsername("testuser");
        user.setPasswordHash("$2a$10$hashedvalue");
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        userMapper.insert(user);

        assertThat(user.getId()).isNotNull();

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", "testuser");
        User found = userMapper.selectOne(query);

        assertThat(found).isNotNull();
        assertThat(found.getUsername()).isEqualTo("testuser");
        assertThat(found.getStatus()).isEqualTo(User.STATUS_ACTIVE);
    }

    @Test
    void selectOne_nonexistent_returnsNull() {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", "no_such_user");
        assertThat(userMapper.selectOne(query)).isNull();
    }
}
