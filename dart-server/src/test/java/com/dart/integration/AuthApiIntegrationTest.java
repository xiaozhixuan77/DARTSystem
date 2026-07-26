package com.dart.integration;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dart.dto.request.LoginRequest;
import com.dart.dto.request.RefreshTokenRequest;
import com.dart.dto.request.RegisterRequest;
import com.dart.entity.User;
import com.dart.mapper.RefreshTokenMapper;
import com.dart.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthApiIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;

    @Test
    @DisplayName("正常注册")
    void registerWithValidInputSucceeds() throws Exception {
        var req = newRegisterRequest("alice", "pass123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.username").value("alice"));

        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", "alice"));
        assertThat(user).isNotNull();
        assertThat(user.getPasswordHash()).startsWith("$2a$");
    }

    @Test
    @DisplayName("重复用户名注册")
    void registerWithDuplicateUsernameReturns2001() throws Exception {
        registerUser("bob", "pass123");
        var req = newRegisterRequest("bob", "pass456");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("非法用户名格式")
    void registerWithInvalidUsernameReturns400() throws Exception {
        var req = newRegisterRequest("ab", "pass123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("密码过短")
    void registerWithShortPasswordReturns400() throws Exception {
        var req = newRegisterRequest("charlie", "12");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("正常登录返回 Token")
    void loginWithCorrectCredentialsReturnsTokens() throws Exception {
        registerUser("dave", "secret1");
        var req = newLoginRequest("dave", "secret1");

        String respBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.username").value("dave"))
                .andReturn().getResponse().getContentAsString();

        // 验证 refresh token 存在且有效
        String refreshToken = objectMapper.readTree(respBody).get("data").get("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();
        var refreshReq = newRefreshTokenRequest(refreshToken);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("密码错误返回 2004")
    void loginWithWrongPasswordReturns2004() throws Exception {
        registerUser("eve", "correct1");
        var req = newLoginRequest("eve", "wrongpass");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2004));

        User user = userMapper.selectOne(
                new QueryWrapper<User>()
                        .eq("username", "eve"));
        assertThat(user.getLoginAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("5 次登录失败后锁定账户")
    void loginLockAfterFiveFailures() throws Exception {
        registerUser("frank", "right1");
        var wrongReq = newLoginRequest("frank", "wrongpass");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongReq)))
                    .andExpect(jsonPath("$.code").value(2004));
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(jsonPath("$.code").value(2004));

        User user = userMapper.selectOne(
                new QueryWrapper<User>()
                        .eq("username", "frank"));
        assertThat(user.getStatus()).isEqualTo(User.STATUS_LOCKED);
        assertThat(user.getLockedUntil()).isNotNull();
        assertThat(user.getLoginAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("已撤销的 Refresh Token 返回 2006")
    void refreshTokenWithRevokedTokenReturns2006() throws Exception {
        registerUser("heidi", "revoked1");
        String loginResp = loginAndGetResponse("heidi", "revoked1");
        String refreshToken = objectMapper.readTree(loginResp).get("data").get("refreshToken").asText();

        var firstReq = newRefreshTokenRequest(refreshToken);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        var secondReq = newRefreshTokenRequest(refreshToken);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2006));
    }

    @Test
    @DisplayName("注册→登录→访问受保护端点 完整流程")
    void fullFlowRegisterLoginAndAccessProtected() throws Exception {
        var registerReq = newRegisterRequest("ivan", "fullflow");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        var loginReq = newLoginRequest("ivan", "fullflow");
        String loginResp = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(loginResp).get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ────────── 工厂方法 ──────────

    private static RegisterRequest newRegisterRequest(String username, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private static LoginRequest newLoginRequest(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private static RefreshTokenRequest newRefreshTokenRequest(String token) {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken(token);
        return req;
    }

}
