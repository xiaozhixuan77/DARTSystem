package com.dart.controller;

import com.dart.dto.request.LoginRequest;
import com.dart.dto.request.RefreshTokenRequest;
import com.dart.dto.request.RegisterRequest;
import com.dart.dto.response.LoginResponse;
import com.dart.dto.response.RegisterResponse;
import com.dart.dto.response.TokenRefreshResponse;
import com.dart.exception.BusinessException;
import com.dart.exception.ErrorCode;
import com.dart.exception.GlobalExceptionHandler;
import com.dart.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthService authService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void register_withValidInput_returns200() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("pass123");

        when(authService.register(any())).thenReturn(new RegisterResponse(1L, "newuser"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    void register_withDuplicateUsername_returns2001() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("existing");
        req.setPassword("pass123");

        when(authService.register(any()))
                .thenThrow(new BusinessException(ErrorCode.USERNAME_EXISTS));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_EXISTS.getCode()));
    }

    @Test
    void register_withInvalidUsername_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("ab");
        req.setPassword("pass123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    void login_withValidCredentials_returns200() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("user1");
        req.setPassword("correct");

        when(authService.login(any())).thenReturn(
                new LoginResponse("access.abc", "refresh.xyz", 7200, 1L, "user1"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access.abc"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh.xyz"));
    }

    @Test
    void login_withBadCredentials_returns2004() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("user1");
        req.setPassword("wrong");

        when(authService.login(any()))
                .thenThrow(new BusinessException(ErrorCode.LOGIN_FAILED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_FAILED.getCode()));
    }

    @Test
    void refreshToken_withValidToken_returns200() throws Exception {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("valid.refresh");

        when(authService.refreshToken(any())).thenReturn(
                new TokenRefreshResponse("new.access", "new.refresh", 7200));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new.access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new.refresh"));
    }
}
