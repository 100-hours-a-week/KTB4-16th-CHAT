package com.ktb4.team16.mulo.chat.exception;

import java.util.UUID;

/** 같은 버전이 서로 다른 이름을 가리키는 경우 호출자에게 전달한다. */
public class ChatNicknameVersionConflictException extends RuntimeException {
    private final Long userId;
    private final long nicknameVersion;
    private final UUID eventId;

    /** 충돌을 추적할 식별자만 보관하고 닉네임 원문은 예외 메시지에 넣지 않는다. */
    public ChatNicknameVersionConflictException(Long userId, long nicknameVersion, UUID eventId) {
        super("동일한 닉네임 버전에 다른 이름이 전달되었습니다.");
        this.userId = userId;
        this.nicknameVersion = nicknameVersion;
        this.eventId = eventId;
    }

    /** 충돌한 사용자를 반환한다. */
    public Long userId() {
        return userId;
    }

    /** 충돌한 변경 버전을 반환한다. */
    public long nicknameVersion() {
        return nicknameVersion;
    }

    /** 재처리나 추적에 사용할 원본 이벤트 ID를 반환한다. */
    public UUID eventId() {
        return eventId;
    }
}
