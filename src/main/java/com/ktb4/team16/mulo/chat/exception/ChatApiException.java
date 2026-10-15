package com.ktb4.team16.mulo.chat.exception;

import org.springframework.http.HttpStatus;

public class ChatApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ChatApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
