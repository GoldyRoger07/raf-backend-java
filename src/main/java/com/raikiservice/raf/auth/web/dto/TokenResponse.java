package com.raikiservice.raf.auth.web.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn,
            UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, user);
    }
}
