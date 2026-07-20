package com.dart.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.entity.User;
import com.dart.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User user = userMapper.selectOne(query);
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPasswordHash(),
                user.getStatus() == User.STATUS_ACTIVE,
                true,
                true,
                user.getStatus() != User.STATUS_LOCKED,
                Collections.emptyList()
        );
    }
}
