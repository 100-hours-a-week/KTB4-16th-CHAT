package com.ktb4.team16.mulo.chat.dto.request;

import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Backend 내부 요청에서 userId 경로 외에 닉네임 이벤트의 순서와 중복 키를 전달한다. */
public record ChatNicknameVersionRequest(
        @NotBlank @Size(max = 10) String nickname,
        @PositiveOrZero long nicknameVersion,
        @NotNull UUID eventId) {

    /** 경로의 사용자 ID와 본문을 기존 버전 적용 서비스 입력으로 결합한다. */
    public ChatNicknameVersionCommand toCommand(Long userId) {
        return new ChatNicknameVersionCommand(userId, nickname, nicknameVersion, eventId);
    }
}
