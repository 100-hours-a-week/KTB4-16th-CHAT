package com.ktb4.team16.mulo.chat.auth.exception;

import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;

public class StompAuthenticationException extends RuntimeException {

    private final ChatErrorCode errorCode;

    public StompAuthenticationException(ChatErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public String getCode() {
        return errorCode.code();
    }

    public static StompAuthenticationException unauthorized() {
        return new StompAuthenticationException(ChatErrorCode.UNAUTHORIZED);
    }

    public static StompAuthenticationException authServiceUnavailable() {
        return new StompAuthenticationException(ChatErrorCode.AUTH_SERVICE_UNAVAILABLE);
    }

    public static StompAuthenticationException protocolViolation() {
        return new StompAuthenticationException(ChatErrorCode.COMMAND_NOT_ALLOWED);
    }

    public static StompAuthenticationException destinationForbidden() {
        return new StompAuthenticationException(ChatErrorCode.STOMP_DESTINATION_FORBIDDEN);
    }
}
