package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.entity.ChatUserNicknameState;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatNicknameStateResolverTest {
    private final ChatUserNicknameStateRepository states = mock(ChatUserNicknameStateRepository.class);
    private final ChatParticipantNicknameService participants = mock(ChatParticipantNicknameService.class);

    /** OFF에서는 버전이 있어도 요청 이름만 반환하고 상태에 접근하지 않는다. */
    @Test
    void disabledDoesNotAccessStateOrParticipants() {
        var resolver = new ChatNicknameStateResolver(new ChatNicknameStateProperties(false), states, participants, Clock.systemUTC());
        var result = resolver.resolveNickname(17L, "봄바람", 8L);
        resolver.reconcileExistingParticipants(17L, result);
        resolver.discardUnusedFirstState(17L, result);
        assertThat(result.nickname()).isEqualTo("봄바람");
        verifyNoInteractions(states, participants);
    }

    /** 무버전 요청의 빈 상태는 새로 만들지 않고 요청 이름을 사용한다. */
    @Test
    void unversionedMissingStateUsesRequestWithoutCreatingState() {
        var resolver = new ChatNicknameStateResolver(new ChatNicknameStateProperties(true), states, participants, Clock.systemUTC());
        when(states.findByUserIdForUpdate(17L)).thenReturn(Optional.empty());
        var result = resolver.resolveNickname(17L, "봄바람", null);
        assertThat(result.nickname()).isEqualTo("봄바람");
        assertThat(result.firstStateCreated()).isFalse();
        verify(states, never()).ensureAndLock(anyLong(), anyString(), anyLong(), any());
    }

    /** 저장된 상태가 있다면 무버전 요청보다 기존 이름이 우선이다. */
    @Test
    void unversionedExistingStateUsesStoredNickname() {
        var state = mock(ChatUserNicknameState.class);
        when(state.getNickname()).thenReturn("여름밤");
        when(states.findByUserIdForUpdate(17L)).thenReturn(Optional.of(state));
        var resolver = new ChatNicknameStateResolver(new ChatNicknameStateProperties(true), states, participants, Clock.systemUTC());
        assertThat(resolver.resolveNickname(17L, "봄바람", null).nickname()).isEqualTo("여름밤");
    }
}
