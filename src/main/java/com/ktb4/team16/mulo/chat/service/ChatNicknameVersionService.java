package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.exception.ChatNicknameVersionConflictException;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class ChatNicknameVersionService {
    private static final Logger log = LoggerFactory.getLogger(ChatNicknameVersionService.class);
    private final ChatUserNicknameStateRepository states;
    private final ChatParticipantNicknameService participants;
    private final Clock clock;

    /** 버전 상태 저장과 기존 참가자 갱신을 같은 서비스 트랜잭션에 연결한다. */
    public ChatNicknameVersionService(
            ChatUserNicknameStateRepository states,
            ChatParticipantNicknameService participants,
            Clock clock) {
        this.states = states;
        this.participants = participants;
        this.clock = clock;
    }

    /** 첫 입력과 새 버전만 반영하고 중복·과거·동일 버전 충돌을 구분한다. */
    @Transactional
    public ChatNicknameVersionResult applyVersionedNickname(
            @Valid @NotNull ChatNicknameVersionCommand command) {
        var now = ChatTime.now(clock);
        var locked = states.ensureAndLock(command, now);
        var state = locked.state();
        if (!locked.created()) {
            if (command.nicknameVersion() < state.getNicknameVersion()) {
                return new ChatNicknameVersionResult(ChatNicknameVersionResult.Status.STALE, 0);
            }
            if (command.nicknameVersion() == state.getNicknameVersion()) {
                if (!command.nickname().equals(state.getNickname())) {
                    log.error("닉네임 버전 충돌: userId={}, nicknameVersion={}, eventId={}",
                            command.userId(), command.nicknameVersion(), command.eventId());
                    throw new ChatNicknameVersionConflictException(
                            command.userId(), command.nicknameVersion(), command.eventId());
                }
                return new ChatNicknameVersionResult(ChatNicknameVersionResult.Status.DUPLICATE, 0);
            }
        }
        // 중요: 최초 INSERT 직후 같은 버전이어도 참가자 이름을 반영해야 한다.
        state.apply(command.nickname(), command.nicknameVersion(), now);
        var result = participants.updateNickname(command.userId(), command.nickname());
        return new ChatNicknameVersionResult(
                ChatNicknameVersionResult.Status.APPLIED, result.updatedParticipantCount());
    }
}
