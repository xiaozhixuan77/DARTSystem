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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenMapper refreshTokenMapper;
    @Mock private JwtProperties jwtProperties;

    @InjectMocks
    private AuthService authService;

    private RegisterRequest validRegister;
    private LoginRequest validLogin;
    private User savedUser;

    @BeforeEach
    void setUp() {
        validRegister = new RegisterRequest();
        validRegister.setUsername("testuser");
        validRegister.setPassword("pass123");

        validLogin = new LoginRequest();
        validLogin.setUsername("testuser");
        validLogin.setPassword("pass123");

        savedUser = new User();
        savedUser.setId(1L);
        savedUser.setUsername("testuser");
        savedUser.setPasswordHash("$2a$10$hashed");
        savedUser.setStatus(User.STATUS_ACTIVE);
        savedUser.setLoginAttempts(0);

        when(jwtProperties.getAccessTokenExpiration()).thenReturn(7200L);
        when(jwtProperties.getRefreshTokenExpiration()).thenReturn(604800L);
    }

    // === register ===

    @Test
    void register_withValidInput_succeeds() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(passwordEncoder.encode("pass123")).thenReturn("$2a$10$hashed");
        doAnswer(inv -> {
            User u = inv.getArgument(0, User.class);
            u.setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        RegisterResponse response = authService.register(validRegister);

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getUsername()).isEqualTo("testuser");
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void register_withDuplicateUsername_throwsException() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);

        assertThatThrownBy(() -> authService.register(validRegister))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_EXISTS);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_withInvalidUsername_throwsException() {
        validRegister.setUsername("ab");
        assertThatThrownBy(() -> authService.register(validRegister))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_INVALID);
    }

    @Test
    void register_withShortPassword_throwsException() {
        validRegister.setPassword("12345");
        assertThatThrownBy(() -> authService.register(validRegister))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_TOO_SHORT);
    }

    @Test
    void register_withLongPassword_throwsException() {
        validRegister.setPassword("a".repeat(73));
        assertThatThrownBy(() -> authService.register(validRegister))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_TOO_SHORT);
    }

    // === login ===

    @Test
    void login_withValidCredentials_returnsTokens() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches("pass123", "$2a$10$hashed")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(1L, "testuser")).thenReturn("access.abc");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("refresh.xyz");
        when(refreshTokenMapper.selectCount(any(QueryWrapper.class))).thenReturn(2L);

        LoginResponse response = authService.login(validLogin);

        assertThat(response.getAccessToken()).isEqualTo("access.abc");
        assertThat(response.getRefreshToken()).isEqualTo("refresh.xyz");
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getUsername()).isEqualTo("testuser");
        verify(refreshTokenMapper).insert(any(RefreshToken.class));
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void login_withWrongPassword_throwsLoginFailed() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches("pass123", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validLogin))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void login_withWrongPassword_incrementsAttempts() {
        savedUser.setLoginAttempts(2);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validLogin));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getLoginAttempts()).isEqualTo(3);
    }

    @Test
    void login_afterFiveFailures_locksAccount() {
        savedUser.setLoginAttempts(4);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validLogin));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getLoginAttempts()).isEqualTo(5);
        assertThat(captor.getValue().getStatus()).isEqualTo(User.STATUS_LOCKED);
        assertThat(captor.getValue().getLockedUntil()).isNotNull();
    }

    @Test
    void login_withLockedAccount_throwsLoginFailed() {
        savedUser.setStatus(User.STATUS_LOCKED);
        savedUser.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);

        assertThatThrownBy(() -> authService.login(validLogin))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void login_withDisabledAccount_throwsLoginFailed() {
        savedUser.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);

        assertThatThrownBy(() -> authService.login(validLogin))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void login_withExpiredLock_succeeds() {
        savedUser.setStatus(User.STATUS_LOCKED);
        savedUser.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        savedUser.setLoginAttempts(5);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(anyLong(), anyString())).thenReturn("access.abc");
        when(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh.xyz");
        when(refreshTokenMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        LoginResponse response = authService.login(validLogin);

        assertThat(response).isNotNull();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(User.STATUS_ACTIVE);
    }

    @Test
    void login_generatesTokens_savesCorrectHash() throws Exception {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(1L, "testuser")).thenReturn("access.abc");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("refresh.xyz");
        when(refreshTokenMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        authService.login(validLogin);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenMapper).insert(captor.capture());
        RefreshToken saved = captor.getValue();

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("refresh.xyz".getBytes(StandardCharsets.UTF_8)));
        assertThat(saved.getTokenHash()).isEqualTo(expectedHash);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getRevoked()).isFalse();
    }

    @Test
    void login_enforcesMaxFiveTokens_deletesOldest() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(savedUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(anyLong(), anyString())).thenReturn("access.new");
        when(jwtTokenProvider.generateRefreshToken(anyLong())).thenReturn("refresh.new");
        when(refreshTokenMapper.selectCount(any(QueryWrapper.class))).thenReturn(5L);

        RefreshToken oldest = new RefreshToken();
        oldest.setId(100L);
        when(refreshTokenMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(oldest));

        authService.login(validLogin);

        verify(refreshTokenMapper).deleteById(100L);
        verify(refreshTokenMapper).insert(any(RefreshToken.class));
    }

    @Test
    void login_withNonexistentUser_throwsLoginFailed() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> authService.login(validLogin))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    // === refresh ===

    @Test
    void refreshToken_withValidToken_returnsNewTokens() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("valid.refresh.token");

        when(jwtTokenProvider.validate("valid.refresh.token")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("valid.refresh.token")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("valid.refresh.token")).thenReturn(1L);
        when(jwtTokenProvider.getUsername("valid.refresh.token")).thenReturn("testuser");

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest("valid.refresh.token".getBytes(StandardCharsets.UTF_8)));

        RefreshToken stored = new RefreshToken();
        stored.setId(10L);
        stored.setTokenHash(expectedHash);
        stored.setRevoked(false);
        when(refreshTokenMapper.selectOne(any(QueryWrapper.class))).thenReturn(stored);

        when(userMapper.selectById(1L)).thenReturn(savedUser);
        when(jwtTokenProvider.generateAccessToken(1L, "testuser")).thenReturn("new.access.abc");
        when(jwtTokenProvider.generateRefreshToken(1L)).thenReturn("new.refresh.xyz");
        when(refreshTokenMapper.updateById(any(RefreshToken.class))).thenReturn(1);
        when(refreshTokenMapper.insert(any(RefreshToken.class))).thenReturn(1);

        TokenRefreshResponse response = authService.refreshToken(request);

        assertThat(response.getAccessToken()).isEqualTo("new.access.abc");
        assertThat(response.getRefreshToken()).isEqualTo("new.refresh.xyz");
        assertThat(response.getExpiresIn()).isEqualTo(7200L);
        verify(refreshTokenMapper).updateById(stored);
    }

    @Test
    void refreshToken_withRevokedToken_throwsException() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("revoked.token");

        when(jwtTokenProvider.validate("revoked.token")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("revoked.token")).thenReturn("refresh");

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest("revoked.token".getBytes(StandardCharsets.UTF_8)));

        RefreshToken revokedRecord = new RefreshToken();
        revokedRecord.setRevoked(true);
        when(refreshTokenMapper.selectOne(any(QueryWrapper.class))).thenReturn(revokedRecord);

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }
}
