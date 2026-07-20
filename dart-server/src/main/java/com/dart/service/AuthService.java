package com.dart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.config.JwtProperties;
import com.dart.dto.request.LoginRequest;
import com.dart.dto.request.RefreshTokenRequest;
import com.dart.dto.request.RegisterRequest;
import com.dart.dto.response.LoginResponse;
import com.dart.dto.response.RegisterResponse;
import com.dart.dto.response.TokenRefreshResponse;
import com.dart.entity.RefreshToken;
import com.dart.entity.User;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.mapper.RefreshTokenMapper;
import com.dart.mapper.UserMapper;
import com.dart.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,20}$");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtProperties jwtProperties;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (request.getUsername() == null || !USERNAME_PATTERN.matcher(request.getUsername()).matches()) {
            throw new BusinessException(ErrorCode.USERNAME_INVALID);
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", request.getUsername());
        if (userMapper.selectOne(query) != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setStatus(User.STATUS_ACTIVE);
        user.setLoginAttempts(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insertUser(user);

        log.info("User registered: id={}, username={}", user.getId(), user.getUsername());
        return new RegisterResponse(user.getId(), user.getUsername());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", request.getUsername());
        User user = userMapper.selectOne(query);
        if (user == null) {
            log.warn("Login failed: user '{}' not found", request.getUsername());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        if (user.getStatus() == User.STATUS_LOCKED) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isBefore(LocalDateTime.now())) {
                user.setStatus(User.STATUS_ACTIVE);
                user.setLoginAttempts(0);
                user.setLockedUntil(null);
                userMapper.updateUser(user);
                log.info("Account unlocked (lock expired): userId={}", user.getId());
            } else {
                log.warn("Login failed: account locked, userId={}", user.getId());
                throw new BusinessException(ErrorCode.LOGIN_FAILED);
            }
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.setLoginAttempts(user.getLoginAttempts() + 1);
            if (user.getLoginAttempts() >= User.MAX_FAILED_ATTEMPTS) {
                user.setStatus(User.STATUS_LOCKED);
                user.setLockedUntil(LocalDateTime.now().plusMinutes(User.LOCK_DURATION_MINUTES));
                log.warn("Account locked after {} failures: userId={}", user.getLoginAttempts(), user.getId());
            }
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateUser(user);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        user.setLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateUser(user);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());
        String tokenHash = sha256(refreshToken);

        QueryWrapper<RefreshToken> countQuery = new QueryWrapper<>();
        countQuery.eq("user_id", user.getId()).eq("revoked", false);
        long tokenCount = refreshTokenMapper.selectCount(countQuery);
        if (tokenCount >= User.MAX_TOKENS_PER_USER) {
            QueryWrapper<RefreshToken> oldestQuery = new QueryWrapper<>();
            oldestQuery.eq("user_id", user.getId())
                       .eq("revoked", false)
                       .orderByAsc("created_at")
                       .last("LIMIT 1");
            List<RefreshToken> oldest = refreshTokenMapper.selectList(oldestQuery);
            if (!oldest.isEmpty()) {
                refreshTokenMapper.deleteById(oldest.get(0).getId());
                log.debug("Deleted oldest refresh token for userId={}", user.getId());
            }
        }

        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(tokenHash);
        rt.setExpiresAt(LocalDateTime.now().plusSeconds(jwtProperties.getRefreshTokenExpiration()));
        rt.setRevoked(false);
        rt.setCreatedAt(LocalDateTime.now());
        refreshTokenMapper.insertToken(rt);

        log.info("User logged in: userId={}", user.getId());
        return new LoginResponse(accessToken, refreshToken,
                jwtProperties.getAccessTokenExpiration(), user.getId(), user.getUsername());
    }

    public TokenRefreshResponse refreshToken(RefreshTokenRequest request) {
        String rawToken = request.getRefreshToken();

        if (!jwtTokenProvider.validate(rawToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        if (!"refresh".equals(jwtTokenProvider.getTokenType(rawToken))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        String tokenHash = sha256(rawToken);
        QueryWrapper<RefreshToken> query = new QueryWrapper<>();
        query.eq("token_hash", tokenHash).eq("revoked", false);
        RefreshToken stored = refreshTokenMapper.selectOne(query);
        if (stored == null) {
            log.warn("Refresh token revoked or not found: hash={}", tokenHash);
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        Long userId = jwtTokenProvider.getUserId(rawToken);
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != User.STATUS_ACTIVE) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, user.getUsername());
        return new TokenRefreshResponse(newAccessToken, jwtProperties.getAccessTokenExpiration());
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
