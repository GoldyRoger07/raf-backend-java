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
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** Parametre de requete ou en-tete absent : erreur du client, pas du serveur. */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> handleMissingParameter(Exception ex, HttpServletRequest request) {
        String name = ex instanceof MissingServletRequestParameterException m ? m.getParameterName()
                : ((MissingRequestHeaderException) ex).getHeaderName();
        return badRequest(ErrorCode.MALFORMED_REQUEST, request, new ApiError.FieldError(name, "Parametre obligatoire."));
    }

    /** Parametre present mais illisible (ex. du texte a la place d'un identifiant). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return badRequest(ErrorCode.MALFORMED_REQUEST, request,
                new ApiError.FieldError(ex.getName(), "Valeur invalide."));
    }

    /** Contraintes de validation posees sur les parametres d'une methode (@Min, @Size...). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        ApiError.FieldError[] fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(e -> new ApiError.FieldError(
                        r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toArray(ApiError.FieldError[]::new);
        return badRequest(ErrorCode.VALIDATION_ERROR, request, fields);
    }

    /** Fichier au-dela de spring.servlet.multipart.max-file-size : refuse avant le controleur. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadTooLarge(HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.MEDIA_TOO_LARGE.getStatus()).body(ApiError.of(
                ErrorCode.MEDIA_TOO_LARGE, ErrorCode.MEDIA_TOO_LARGE.getDefaultMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage(),
                request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of(
                ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(),
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

    private static ResponseEntity<ApiError> badRequest(ErrorCode code, HttpServletRequest request,
            ApiError.FieldError... fields) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(
                code, code.getDefaultMessage(), request.getRequestURI(), List.of(fields)));
    }
}
