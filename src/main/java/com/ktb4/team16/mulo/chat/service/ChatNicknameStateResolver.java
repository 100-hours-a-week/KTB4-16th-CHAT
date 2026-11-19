package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatNicknameStateResolver {
    private final ChatNicknameStateProperties properties;
    private final ChatUserNicknameStateRepository states;
    private final ChatParticipantNicknameService participants;
    private final Clock clock;

    /** 설정, 상태 저장소와 참가자 보정 기능을 연결한다. */
    public ChatNicknameStateResolver(
            ChatNicknameStateProperties properties,
            ChatUserNicknameStateRepository states,
            ChatParticipantNicknameService participants,
            Clock clock) {
        this.properties = properties;
        this.states = states;
        this.participants = participants;
        this.clock = clock;
    }

    /** 설정과 저장된 버전을 기준으로 새 방 참가자 이름을 결정한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Resolution resolveNickname(Long userId, String requestedNickname, Long requestedVersion) {
        if (!properties.enabled()) {
            return new Resolution(requestedNickname, false);
        }
        if (requestedVersion == null) {
            Optional<String> storedNickname = states.findByUserIdForUpdate(userId)
                    .map(state -> state.getNickname());
            return new Resolution(storedNickname.orElse(requestedNickname), false);
        }

        var locked = states.ensureAndLock(userId, requestedNickname,
                requestedVersion, ChatTime.now(clock));
        return new Resolution(locked.state().getNickname(), locked.created());
    }

    /** 새 방·배정이 확정된 경우 첫 상태와 기존 방 이름을 함께 맞춘다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileExistingParticipants(Long userId, Resolution resolution) {
        if (properties.enabled() && resolution.firstStateCreated()) {
            participants.updateNickname(userId, resolution.nickname());
        }
    }

    /** 그룹에서 기존 배정을 재발견하면 이 요청이 새로 만든 상태만 지운다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void discardUnusedFirstState(Long userId, Resolution resolution) {
        if (properties.enabled() && resolution.firstStateCreated()) {
            states.deleteByUserId(userId);
        }
    }

    public record Resolution(String nickname, boolean firstStateCreated) {
    }
}
