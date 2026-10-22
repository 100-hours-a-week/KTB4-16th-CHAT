package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.dto.request.UpdateNicknameRequest;
import com.ktb4.team16.mulo.chat.dto.response.UpdateNicknameResponse;
import com.ktb4.team16.mulo.chat.service.ChatParticipantNicknameService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/internal/users")
public class InternalUserController {

    private final ChatParticipantNicknameService nicknameService;

    public InternalUserController(ChatParticipantNicknameService nicknameService) {
        this.nicknameService = nicknameService;
    }

    @PatchMapping("/{userId}/nickname")
    public UpdateNicknameResponse updateNickname(
            @PathVariable @Positive Long userId,
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        return nicknameService.updateNickname(userId, request.nickname());
    }
}
