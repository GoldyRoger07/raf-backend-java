package com.raikiservice.raf.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(

        @NotBlank(message = "Le refresh token est obligatoire.")
        String refreshToken) {
}
