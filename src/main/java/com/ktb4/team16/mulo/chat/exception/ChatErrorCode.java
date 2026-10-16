package com.ktb4.team16.mulo.chat.exception;

import org.springframework.http.HttpStatus;

/** Chat Server가 REST와 STOMP 응답에 사용하는 공개 오류 코드와 기본 정보를 관리한다. */
public enum ChatErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    AUTH_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "인증 서비스를 일시적으로 이용할 수 없습니다."),
    AUTH_SERVICE_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "인증 서비스 응답을 처리할 수 없습니다."),
    AUTH_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "인증 서비스 응답 시간이 초과되었습니다."),
    AUTH_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "인증 서비스를 처리할 수 없습니다."),
    INTERNAL_AUTH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "내부 인증 설정을 사용할 수 없습니다."),
    COMMAND_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않은 STOMP 명령입니다."),
    STOMP_DESTINATION_FORBIDDEN(HttpStatus.FORBIDDEN, "허용되지 않은 STOMP 목적지입니다."),
    CHAT_SEND_FORBIDDEN(HttpStatus.FORBIDDEN, "채팅방에서 메시지를 보낼 수 없습니다."),
    CHAT_ROOM_EXPIRED(HttpStatus.FORBIDDEN, "만료된 채팅방에서는 메시지를 보낼 수 없습니다."),
    CLIENT_MESSAGE_ID_CONFLICT(HttpStatus.CONFLICT, "이미 다른 메시지에 사용된 clientMessageId입니다."),
    MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST, "메시지는 500자 이하여야 합니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요."),
    INVALID_CHAT_ROOM_ID(HttpStatus.BAD_REQUEST, "올바른 채팅방 ID를 입력해주세요."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "올바른 커서를 입력해주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "지원하지 않는 응답 형식입니다."),
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    CHAT_ALREADY_LEFT(HttpStatus.CONFLICT, "이미 퇴장한 채팅방입니다."),
    CHAT_ALREADY_LEFT_FORBIDDEN(HttpStatus.FORBIDDEN, "CHAT_ALREADY_LEFT", "이미 퇴장한 채팅방입니다."),
    CHAT_REENTRY_FORBIDDEN(HttpStatus.FORBIDDEN, "퇴장한 채팅방에는 다시 입장할 수 없습니다."),
    STOMP_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "메시지를 전송할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");

    private final String code;
    private final HttpStatus status;
    private final String message;

    ChatErrorCode(HttpStatus status, String message) {
        this.code = name();
        this.status = status;
        this.message = message;
    }

    ChatErrorCode(HttpStatus status, String code, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
