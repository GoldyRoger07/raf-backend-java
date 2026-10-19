package com.raikiservice.raf.auth.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;

public record UserResponse(
        Long id,
        String email,
        String phone,
        String firstName,
        String lastName,
        UserStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        List<String> roles,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getRoles().stream().map(role -> role.getName().name()).sorted().toList(),
                user.getCreatedAt());
    }
}
