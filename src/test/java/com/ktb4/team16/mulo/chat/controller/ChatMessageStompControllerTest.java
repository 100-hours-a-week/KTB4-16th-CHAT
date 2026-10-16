package com.ktb4.team16.mulo.chat.controller;

import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.auth.security.ChatPrincipal;
import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.service.ChatMessageSendService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ChatMessageStompControllerTest {

    private final ChatMessageSendService sendService = Mockito.mock(ChatMessageSendService.class);
    private final ChatMessageStompController controller = new ChatMessageStompController(sendService);

    @Test
    void delegatesChatRoomIdAuthenticatedUserAndValidatedRequestToService() {
        ChatMessageSendRequest request = new ChatMessageSendRequest(UUID.randomUUID(), "안녕하세요");

        controller.sendMessage(23L, request, new ChatPrincipal(17L));

        verify(sendService).send(23L, 17L, request);
    }
}
