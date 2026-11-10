package com.ktb4.team16.mulo.chat.service;

/** 호출자가 재전달과 과거 입력을 구분할 수 있는 내부 처리 결과다. */
public record ChatNicknameVersionResult(Status status, int updatedParticipantCount) {
    public enum Status {
        APPLIED,
        DUPLICATE,
        STALE
    }
}
