package com.dart.integration;

import com.dart.entity.User;
import com.dart.mapper.UserMapper;
import com.dart.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("无 Token 访问受保护端点被拒绝")
    void accessProtectedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("格式错误 Token 被拒绝")
    void accessProtectedWithMalformedToken() throws Exception {
        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "NotBearer xyz"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("被篡改 Token 被拒绝")
    void accessProtectedWithTamperedToken() throws Exception {
        registerUser("sec1", "pass123");
        String token = loginAndGetAccessToken("sec1", "pass123");
        String tampered = token.substring(0, token.length() - 4) + "xxxx";

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Refresh Token 作为 Access Token 被拒绝")
    void accessProtectedWithRefreshToken() throws Exception {
        registerUser("sec2", "pass123");
        Long userId = getUserId("sec2");
        String refreshToken = jwtTokenProvider.generateRefreshToken(userId);

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("密码以 BCrypt 存储")
    void passwordIsStoredAsBcrypt() throws Exception {
        registerUser("sec3", "bcryptpw");

        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                        .eq("username", "sec3"));
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches("bcryptpw", user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("wrongpw", user.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("用户数据隔离 — Alice 不能访问 Bob 的会话")
    void userACannotAccessUserBConversationMessages() throws Exception {
        registerUser("alice", "pass123");
        registerUser("bob", "pass456");

        String aliceToken = loginAndGetAccessToken("alice", "pass123");
        String bobToken = loginAndGetAccessToken("bob", "pass456");

        // Bob 创建会话
        var bobResp = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int bobConvId = objectMapper.readTree(bobResp).get("data").get("id").asInt();

        // Alice 访问 Bob 的会话 → 返回 403（FORBIDDEN，数据所有权校验）
        mockMvc.perform(get("/api/conversations/" + bobConvId + "/messages")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("认证端点无需 Token")
    void authEndpointsDoNotRequireToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sec4\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("不存在的知识条目返回 5001")
    void unknownKnowledgeReturnsKnowledgeNotFound() throws Exception {
        registerUser("sec5", "pass123");
        String token = loginAndGetAccessToken("sec5", "pass123");

        mockMvc.perform(get("/api/knowledge/99999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(5001));
    }

    private Long getUserId(String username) {
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                        .eq("username", username));
        return user != null ? user.getId() : null;
    }
}
