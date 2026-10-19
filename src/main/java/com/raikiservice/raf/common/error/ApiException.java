package com.raikiservice.raf.common.error;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, resource + " introuvable.");
    }
}
