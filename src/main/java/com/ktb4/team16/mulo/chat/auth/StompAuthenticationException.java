package com.ktb4.team16.mulo.chat.auth;

public class StompAuthenticationException extends RuntimeException {

    private final String code;

    public StompAuthenticationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static StompAuthenticationException unauthorized() {
        return new StompAuthenticationException("UNAUTHORIZED", "UNAUTHORIZED");
    }

    public static StompAuthenticationException authServiceUnavailable() {
        return new StompAuthenticationException("AUTH_SERVICE_UNAVAILABLE", "AUTH_SERVICE_UNAVAILABLE");
    }

    public static StompAuthenticationException protocolViolation() {
        return new StompAuthenticationException("COMMAND_NOT_ALLOWED", "COMMAND_NOT_ALLOWED");
    }
}
