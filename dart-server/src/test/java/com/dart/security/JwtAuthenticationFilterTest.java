package com.dart.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserDetailsService userDetailsService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_withNoAuthHeader_continuesChain() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenProvider, never()).validate(anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_withValidBearerToken_setsAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid.token.here");
        when(jwtTokenProvider.validate("valid.token.here")).thenReturn(true);
        when(jwtTokenProvider.getUsername("valid.token.here")).thenReturn("testuser");

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "testuser", "hashed", Collections.emptyList());
        when(userDetailsService.loadUserByUsername("testuser")).thenReturn(userDetails);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("testuser");
    }

    @Test
    void doFilterInternal_withInvalidToken_continuesWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad.token");
        when(jwtTokenProvider.validate("bad.token")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doFilterInternal_withNonBearerPrefix_continuesWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenProvider, never()).validate(anyString());
    }

    @Test
    void doFilterInternal_withMalformedToken_continuesWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer not.valid");
        when(jwtTokenProvider.validate("not.valid")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldNotFilter_returnsTrueForAuthPath() {
        when(request.getServletPath()).thenReturn("/api/auth/login");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_returnsTrueForSwaggerPath() {
        when(request.getServletPath()).thenReturn("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getServletPath()).thenReturn("/v3/api-docs");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        when(request.getServletPath()).thenReturn("/doc.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_returnsFalseForProtectedPath() {
        when(request.getServletPath()).thenReturn("/api/knowledge");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
