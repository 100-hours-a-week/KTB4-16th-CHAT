package com.ktb4.team16.mulo.chat.chat;

public class ChatRoomAccessException extends RuntimeException {

    private final Reason reason;

    public ChatRoomAccessException(Reason reason) {
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        ROOM_NOT_FOUND,
        PARTICIPANT_NOT_FOUND,
        PARTICIPANT_NOT_JOINED,
        PARTICIPANT_LEFT,
        ROOM_EXPIRED
    }
}
