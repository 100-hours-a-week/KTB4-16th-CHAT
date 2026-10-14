package com.ktb4.team16.mulo.chat.auth;

import org.springframework.http.HttpStatus;

public class BackendAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String responseMessage;

    public BackendAuthException(HttpStatus status, String code, String responseMessage) {
        super(responseMessage);
        this.status = status;
        this.code = code;
        this.responseMessage = responseMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public static BackendAuthException unauthorized() {
        return new BackendAuthException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
    }

    public static BackendAuthException forbidden() {
        return new BackendAuthException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
    }

    public static BackendAuthException unavailable() {
        return new BackendAuthException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_SERVICE_UNAVAILABLE",
                "인증 서비스를 일시적으로 이용할 수 없습니다."
        );
    }

    public static BackendAuthException invalidResponse() {
        return new BackendAuthException(
                HttpStatus.BAD_GATEWAY,
                "AUTH_SERVICE_BAD_RESPONSE",
                "인증 서비스 응답을 처리할 수 없습니다."
        );
    }
}
