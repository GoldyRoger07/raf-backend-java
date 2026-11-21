package com.raikiservice.raf.common.error;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.common.i18n.MessageTranslator;

import jakarta.servlet.http.HttpServletRequest;

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

    /** Meme erreur, messages dans la langue demandee par le client (francais par defaut). */
    public ApiError translated(MessageTranslator translator, HttpServletRequest request) {
        if (!MessageTranslator.wantsCreole(request)) {
            return this;
        }
        List<FieldError> fields = fieldErrors == null ? null : fieldErrors.stream()
                .map(f -> new FieldError(f.field(), translator.translate(f.message(), request)))
                .toList();
        return new ApiError(timestamp, status, code, translator.translate(message, request), path, fields);
    }
}
