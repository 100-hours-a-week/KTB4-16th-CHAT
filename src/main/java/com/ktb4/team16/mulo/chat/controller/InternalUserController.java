package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.dto.request.ChatNicknameVersionRequest;
import com.ktb4.team16.mulo.chat.dto.request.UpdateNicknameRequest;
import com.ktb4.team16.mulo.chat.dto.response.UpdateNicknameResponse;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionResult;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionService;
import com.ktb4.team16.mulo.chat.service.ChatParticipantNicknameService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/internal/users")
public class InternalUserController {

    private final ChatParticipantNicknameService nicknameService;
    private final ChatNicknameVersionService nicknameVersionService;

    /** 레거시 닉네임 API와 버전 적용 API의 서비스 의존성을 연결한다. */
    public InternalUserController(
            ChatParticipantNicknameService nicknameService,
            ChatNicknameVersionService nicknameVersionService) {
        this.nicknameService = nicknameService;
        this.nicknameVersionService = nicknameVersionService;
    }

    @PatchMapping("/{userId}/nickname")
    public UpdateNicknameResponse updateNickname(
            @PathVariable @Positive Long userId,
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        return nicknameService.updateNickname(userId, request.nickname());
    }

    /** 내부 MQ 중계가 전달한 버전 이벤트를 멱등하게 Chat 상태와 참가자에 적용한다. */
    @PatchMapping("/{userId}/nickname/versioned")
    public ChatNicknameVersionResult updateVersionedNickname(
            @PathVariable @Positive Long userId,
            @Valid @RequestBody ChatNicknameVersionRequest request
    ) {
        return nicknameVersionService.applyVersionedNickname(request.toCommand(userId));
    }
}
