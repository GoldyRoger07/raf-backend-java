package com.raikiservice.raf.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

/**
 * Emission et verification des jetons d'acces.
 *
 * <p>Le jeton d'acces est auto-porteur et a duree de vie courte : il n'est jamais
 * revoque individuellement. La revocation passe par le refresh token, qui lui est
 * stocke en base.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long accessTtlSeconds;

    public JwtService(AppProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET doit faire au moins 32 caracteres pour HS256 (actuel : " + secret.length + ").");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = properties.jwt().issuer();
        this.accessTtlSeconds = properties.jwt().accessTokenTtl().getSeconds();
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(role -> role.getName().name()).toList();

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .signWith(key)
                .compact();
    }

    public long accessTokenTtlSeconds() {
        return accessTtlSeconds;
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            List<String> roles = claims.get("roles", List.class);
            return new UserPrincipal(
                    Long.valueOf(claims.getSubject()),
                    claims.get("email", String.class),
                    roles == null ? List.of() : roles);
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }
    }
}
