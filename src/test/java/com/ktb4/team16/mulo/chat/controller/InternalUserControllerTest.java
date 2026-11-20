package com.ktb4.team16.mulo.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.ChatNicknameVersionRequest;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionCommand;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionResult;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionService;
import com.ktb4.team16.mulo.chat.service.ChatParticipantNicknameService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalUserControllerTest {
    private final ChatParticipantNicknameService nicknameService = mock(ChatParticipantNicknameService.class);
    private final ChatNicknameVersionService versionService = mock(ChatNicknameVersionService.class);
    private final InternalUserController controller = new InternalUserController(nicknameService, versionService);

    /** 경로 ID와 이벤트 본문을 기존 버전 적용 서비스에 전달한다. */
    @Test
    void delegatesVersionedNicknameRequest() {
        var eventId = UUID.randomUUID();
        var request = new ChatNicknameVersionRequest("새이름", 8, eventId);
        var expected = new ChatNicknameVersionResult(ChatNicknameVersionResult.Status.APPLIED, 2);
        when(versionService.applyVersionedNickname(new ChatNicknameVersionCommand(17L, "새이름", 8, eventId)))
                .thenReturn(expected);

        var result = controller.updateVersionedNickname(17L, request);

        assertThat(result).isEqualTo(expected);
        verify(versionService).applyVersionedNickname(new ChatNicknameVersionCommand(17L, "새이름", 8, eventId));
    }
}
