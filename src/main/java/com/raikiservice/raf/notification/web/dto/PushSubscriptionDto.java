package com.raikiservice.raf.notification.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Abonnement push tel que le renvoie {@code PushSubscription.toJSON()} dans le
 * navigateur : {@code { endpoint, keys: { p256dh, auth } }}.
 */
public record PushSubscriptionDto(

        @NotBlank
        @Size(max = 2000)
        @Pattern(regexp = "^https://.+", message = "L'endpoint doit etre une URL https.")
        String endpoint,

        @NotNull
        @Valid
        Keys keys) {

    public record Keys(

            @NotBlank
            @Size(max = 200)
            String p256dh,

            @NotBlank
            @Size(max = 100)
            String auth) {
    }
}
