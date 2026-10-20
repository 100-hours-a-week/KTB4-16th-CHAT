package com.ktb4.team16.mulo.chat.auth.exception;

import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import org.springframework.http.HttpStatus;

public class BackendAuthException extends RuntimeException {

    private final ChatErrorCode errorCode;

    public BackendAuthException(ChatErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return errorCode.status();
    }

    public String getCode() {
        return errorCode.code();
    }

    public String getResponseMessage() {
        return errorCode.message();
    }

    public ChatErrorCode getErrorCode() {
        return errorCode;
    }

    public static BackendAuthException unauthorized() {
        return new BackendAuthException(ChatErrorCode.UNAUTHORIZED);
    }

    public static BackendAuthException forbidden() {
        return new BackendAuthException(ChatErrorCode.FORBIDDEN);
    }

    public static BackendAuthException unavailable() {
        return new BackendAuthException(ChatErrorCode.AUTH_SERVICE_UNAVAILABLE);
    }

    public static BackendAuthException invalidResponse() {
        return new BackendAuthException(ChatErrorCode.AUTH_SERVICE_BAD_RESPONSE);
    }
}
