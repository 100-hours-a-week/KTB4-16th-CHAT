package com.ktb4.team16.mulo.chat.moderation.client;

public class ChatModerationUnavailableException extends RuntimeException {

    public ChatModerationUnavailableException() {
        super("AI 모더레이션 응답을 확인할 수 없습니다.");
    }
}
