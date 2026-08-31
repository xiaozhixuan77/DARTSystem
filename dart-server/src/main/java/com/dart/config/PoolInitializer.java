package com.dart.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.User;
import com.dart.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 无登录模式数据池初始化：确保用户数据池（xiao, id=3）与测试数据池（pool_test）存在，
 * 供 PoolConfig/settings 端点映射。password_hash 为占位值（无密码登录）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoolInitializer implements ApplicationRunner {

    public static final String USER_POOL_USERNAME = "xiao";
    public static final String TEST_POOL_USERNAME = "pool_test";
    public static final long USER_POOL_USER_ID = 3L;

    private final UserMapper userMapper;

    @Override
    public void run(ApplicationArguments args) {
        ensureUser(USER_POOL_USERNAME, USER_POOL_USER_ID);
        ensureUser(TEST_POOL_USERNAME, null);
    }

    private void ensureUser(String username, Long fixedId) {
        User existing = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", username));
        if (existing != null) {
            return;
        }
        User user = new User();
        user.setId(fixedId);
        user.setUsername(username);
        user.setPasswordHash("no-auth");
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("无登录数据池用户已创建: username={}, id={}", username, user.getId());
    }
}
