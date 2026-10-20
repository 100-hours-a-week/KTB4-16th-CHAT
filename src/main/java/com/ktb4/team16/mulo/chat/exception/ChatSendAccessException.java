package com.ktb4.team16.mulo.chat.exception;

/** SEND 접근 예외의 기존 분류를 유지하면서 제한된 진단 맥락만 보관한다. */
public class ChatSendAccessException extends ChatRoomAccessException {

    private final ChatSendDiagnostic diagnostic;

    public ChatSendAccessException(Reason reason, ChatSendDiagnostic diagnostic) {
        super(reason);
        this.diagnostic = diagnostic;
    }

    public ChatSendDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
