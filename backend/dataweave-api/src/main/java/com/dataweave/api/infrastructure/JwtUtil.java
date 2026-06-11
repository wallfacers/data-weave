package com.dataweave.api.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具：签发 / 解析 / 验证。HS256 对称密钥，密钥经 {@code jwt.secret} 配置。
 *
 * <p>payload 包含：sub(userId)、tenantId、username、roles。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtUtil(
            @Value("${jwt.secret:dataweave-local-jwt-secret-key-must-be-at-least-32-chars}") String secret,
            @Value("${jwt.expiration-seconds:86400}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    /** 签发 JWT。 */
    public String generate(Long userId, Long tenantId, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    /** 解析并验证 JWT，返回 claims；失败返回 null。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 从 claims 提取 userId。 */
    public Long userId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /** 从 claims 提取 tenantId。 */
    public Long tenantId(Claims claims) {
        return claims.get("tenantId", Long.class);
    }

    /** 从 claims 提取 username。 */
    public String username(Claims claims) {
        return claims.get("username", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> roles(Claims claims) {
        return claims.get("roles", List.class);
    }
}
