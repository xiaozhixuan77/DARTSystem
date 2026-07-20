package com.dart.security;

import com.dart.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-32bytes-ok".getBytes()));
        props.setAccessTokenExpiration(300);
        props.setRefreshTokenExpiration(1800);
        props.validate();
        provider = new JwtTokenProvider(props);
        key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(props.getSecret()));
    }

    @Test
    void generateAccessToken_returnsNonNullString() {
        String token = provider.generateAccessToken(1L, "testuser");
        assertThat(token).isNotBlank();
    }

    @Test
    void generateAccessToken_containsCorrectClaims() {
        String token = provider.generateAccessToken(42L, "alice");
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void generateRefreshToken_containsCorrectClaims() {
        String token = provider.generateRefreshToken(7L);
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.get("username", String.class)).isNull();
    }

    @Test
    void validate_withValidAccessToken_returnsTrue() {
        String token = provider.generateAccessToken(1L, "test");
        assertThat(provider.validate(token)).isTrue();
    }

    @Test
    void validate_withValidRefreshToken_returnsTrue() {
        String token = provider.generateRefreshToken(1L);
        assertThat(provider.validate(token)).isTrue();
    }

    @Test
    void validate_withExpiredToken_returnsFalse() throws Exception {
        JwtProperties shortProps = new JwtProperties();
        shortProps.setSecret(Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-32bytes-ok".getBytes()));
        shortProps.setAccessTokenExpiration(0);
        shortProps.validate();
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortProps);
        String token = shortProvider.generateAccessToken(1L, "test");
        Thread.sleep(10);
        assertThat(shortProvider.validate(token)).isFalse();
    }

    @Test
    void validate_withTamperedToken_returnsFalse() {
        String token = provider.generateAccessToken(1L, "test");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThat(provider.validate(tampered)).isFalse();
    }

    @Test
    void validate_withEmptyOrNull_returnsFalse() {
        assertThat(provider.validate("")).isFalse();
        assertThat(provider.validate(null)).isFalse();
    }

    @Test
    void validate_withDifferentKey_returnsFalse() {
        String otherKeyB64 = Base64.getEncoder().encodeToString(
                "another-different-secret-key-32b!!".getBytes());
        SecretKey otherKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(otherKeyB64));
        String foreignToken = Jwts.builder()
                .subject("1").claim("type", "access")
                .signWith(otherKey).compact();
        assertThat(provider.validate(foreignToken)).isFalse();
    }

    @Test
    void getUserId_returnsCorrectValue() {
        String token = provider.generateAccessToken(99L, "bob");
        assertThat(provider.getUserId(token)).isEqualTo(99L);
    }

    @Test
    void getUsername_returnsCorrectValue() {
        String token = provider.generateAccessToken(1L, "charlie");
        assertThat(provider.getUsername(token)).isEqualTo("charlie");
    }

    @Test
    void getTokenType_distinguishesAccessAndRefresh() {
        String access = provider.generateAccessToken(1L, "test");
        String refresh = provider.generateRefreshToken(1L);
        assertThat(provider.getTokenType(access)).isEqualTo("access");
        assertThat(provider.getTokenType(refresh)).isEqualTo("refresh");
    }
}
