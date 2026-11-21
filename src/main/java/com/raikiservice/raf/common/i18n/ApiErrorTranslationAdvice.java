package com.raikiservice.raf.common.i18n;

import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.common.error.ApiError;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Traduit toute erreur de l'API juste avant son envoi, ainsi que les courts
 * messages de confirmation ({@link MessageResponse}) : le code reste en francais,
 * la langue ne se decide qu'ici.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiErrorTranslationAdvice implements ResponseBodyAdvice<Object> {

    private final MessageTranslator translator;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        HttpServletRequest httpRequest = servlet.getServletRequest();
        if (body instanceof ApiError error) {
            return error.translated(translator, httpRequest);
        }
        if (body instanceof MessageResponse confirmation) {
            return new MessageResponse(translator.translate(confirmation.message(), httpRequest));
        }
        return body;
    }
}
