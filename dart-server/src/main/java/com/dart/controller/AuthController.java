package com.dart.controller;

import com.dart.dto.request.LoginRequest;
import com.dart.dto.request.RefreshTokenRequest;
import com.dart.dto.request.RegisterRequest;
import com.dart.dto.response.ApiResponse;
import com.dart.dto.response.LoginResponse;
import com.dart.dto.response.RegisterResponse;
import com.dart.dto.response.TokenRefreshResponse;
import com.dart.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refreshToken(request));
    }
}
