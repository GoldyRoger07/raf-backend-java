package com.raikiservice.raf.common.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        if (code.getStatus().is5xxServerError()) {
            log.error("Erreur applicative {} sur {}", code, request.getRequestURI(), ex);
        } else {
            log.debug("Erreur metier {} sur {} : {}", code, request.getRequestURI(), ex.getMessage());
        }
        return ResponseEntity.status(code.getStatus())
                .body(ApiError.of(code, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(
                ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(),
                request.getRequestURI(), fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(
                ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.getDefaultMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
                ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getDefaultMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(
                ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getDefaultMessage(),
                request.getRequestURI()));
    }

    /**
     * Filet de securite : une contrainte d'unicite qui remonte jusqu'ici signale
     * une course entre deux requetes concurrentes, pas une erreur de saisie.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Violation d'integrite sur {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                ErrorCode.CONFLICT, ErrorCode.CONFLICT.getDefaultMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erreur non geree sur {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                request.getRequestURI()));
    }
}
