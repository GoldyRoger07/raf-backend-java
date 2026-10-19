package com.raikiservice.raf.common.error;

import java.time.Instant;
import java.util.List;

/** Corps de reponse unique pour toutes les erreurs de l'API. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), code.getStatus().value(), code.name(), message, path, null);
    }

    public static ApiError of(ErrorCode code, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), code.getStatus().value(), code.name(), message, path, fieldErrors);
    }
}
