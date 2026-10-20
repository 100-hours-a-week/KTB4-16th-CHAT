package com.ktb4.team16.mulo.chat.exception;

/** 제한된 STOMP SEND 진단 분류로, 사용자나 저장소 세부 정보를 포함하지 않는다. */
public record ChatSendDiagnostic(Stage stage, Reason reason) {

    public enum Stage {
        PRECHECK("precheck"),
        TRANSACTION("transaction");

        private final String value;

        Stage(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum Reason {
        INVALID_CHAT_ROOM_ID,
        ROOM_NOT_FOUND,
        ROOM_EXPIRED,
        PARTICIPANT_ROW_NOT_FOUND_DURING_LOCK,
        PARTICIPANT_COUNT_MISMATCH,
        SENDER_NOT_FOUND,
        SENDER_NOT_JOINED,
        SENDER_LEFT,
        PEER_NOT_ACTIVE,
        PEER_NOT_FOUND_AFTER_ACTIVE_CHECK
    }
}
