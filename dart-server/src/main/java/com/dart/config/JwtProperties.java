package com.dart.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Base64;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;
    private long accessTokenExpiration;
    private long refreshTokenExpiration;

    @PostConstruct
    public void validate() {
        Assert.hasText(secret, "jwt.secret must not be empty");
        byte[] decoded = Base64.getDecoder().decode(secret);
        Assert.isTrue(decoded.length >= 32,
                "jwt.secret must decode to at least 32 bytes (256 bits) for HMAC-SHA256");
    }
}
