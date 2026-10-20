package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class ChatSendDiagnosticLoggerTest {

    @Test
    @DisplayName("접근 거부 시 식별 정보와 참가자 상태만 진단 로그에 남긴다")
    void logsSanitizedDenialContext() {
        Logger logger = mock(Logger.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatRoomParticipant sender = ChatRoomParticipant.create(room, 3L, "비공개닉네임");
        sender.enter();
        ChatRoomParticipant peer = ChatRoomParticipant.create(room, 9L, "상대닉네임");

        ChatSendDiagnosticLogger.warn(logger, 1L, 3L, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "precheck", "PEER_NOT_ACTIVE", List.of(sender, peer));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(logger).warn(anyString(), arguments.capture());
        String loggedArguments = Arrays.deepToString(arguments.getValue());
        assertThat(loggedArguments)
                .contains("1", "3", "precheck", "PEER_NOT_ACTIVE", "ParticipantState[userId=9, joined=false,")
                .doesNotContain("비공개닉네임", "상대닉네임");
    }

    @Test
    @DisplayName("진단 로그 기록 중 예외가 발생해도 호출자에게 전파하지 않는다")
    void doesNotPropagateLoggingFailure() {
        Logger logger = mock(Logger.class);
        doThrow(new IllegalStateException("logger failure"))
                .when(logger).warn(anyString(), any(Object[].class));

        assertThatCode(() -> ChatSendDiagnosticLogger.warn(logger, 1L, 3L, UUID.randomUUID(),
                "transaction", "SENDER_LEFT", List.of()))
                .doesNotThrowAnyException();
    }
}
