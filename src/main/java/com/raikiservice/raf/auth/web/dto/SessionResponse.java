package com.raikiservice.raf.auth.web.dto;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.RefreshToken;

/** Une session active = un appareil connecte. */
public record SessionResponse(
        Long id,
        String deviceLabel,
        String ipAddress,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt) {

    public static SessionResponse from(RefreshToken token) {
        return new SessionResponse(
                token.getId(),
                token.getDeviceLabel(),
                token.getIpAddress(),
                token.getCreatedAt(),
                token.getLastUsedAt(),
                token.getExpiresAt());
    }
}
