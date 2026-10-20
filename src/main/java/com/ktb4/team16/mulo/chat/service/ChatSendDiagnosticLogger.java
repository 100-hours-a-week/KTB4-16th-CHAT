package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

final class ChatSendDiagnosticLogger {

    private static final String MESSAGE_TEMPLATE = "STOMP SEND 거부: chatRoomId={}, senderUserId={}, "
            + "clientMessageId={}, stage={}, reason={}, participantCount={}, participants={}";

    private ChatSendDiagnosticLogger() {
    }

    static void warn(
            Logger logger,
            Long chatRoomId,
            Long senderUserId,
            UUID clientMessageId,
            String stage,
            String reason,
            List<ChatRoomParticipant> participants
    ) {
        try {
            Integer participantCount = participants == null ? null : participants.size();
            List<ParticipantState> states = participants == null ? List.of() : participants.stream()
                    .map(ChatSendDiagnosticLogger::toState)
                    .toList();
            logger.warn(MESSAGE_TEMPLATE, chatRoomId, senderUserId, clientMessageId, stage, reason,
                    participantCount, states);
        } catch (RuntimeException ignored) {
            // 진단 로깅 실패가 메시지 권한 처리에 영향을 주지 않도록 한다.
        }
    }

    private static ParticipantState toState(ChatRoomParticipant participant) {
        boolean joined = participant.getJoinedAt() != null;
        boolean left = participant.getLeftAt() != null;
        return new ParticipantState(participant.getUserId(), joined, left, joined && !left);
    }

    private record ParticipantState(Long userId, boolean joined, boolean left, boolean active) {
    }
}
