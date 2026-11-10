package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.response.UpdateNicknameResponse;
import com.ktb4.team16.mulo.chat.entity.ChatUserNicknameState;
import com.ktb4.team16.mulo.chat.exception.ChatNicknameVersionConflictException;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository.LockedNicknameState;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ChatNicknameVersionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime PREVIOUS_TIME = LocalDateTime.of(2026, 11, 8, 12, 0);
    @Mock private ChatUserNicknameStateRepository repository;
    @Mock private ChatParticipantNicknameService participants;
    private ChatUserNicknameState state;
    private ChatNicknameVersionService service;

    /** DB 접근만 대체하고 버전 판단과 상태 변경은 실제 객체로 검증한다. */
    @BeforeEach
    void setUp() throws Exception {
        state = BeanUtils.instantiateClass(ChatUserNicknameState.class.getDeclaredConstructor());
        ReflectionTestUtils.setField(state, "userId", 17L);
        state.apply("여름밤", 8, PREVIOUS_TIME);
        service = new ChatNicknameVersionService(repository, participants, CLOCK);
    }

    /** 최초 INSERT로 이미 같은 버전이 보여도 참가자 이름을 실제로 반영해야 한다. */
    @Test
    void firstMessageAppliesParticipantsInsteadOfBeingSkippedAsDuplicate() {
        var command = command("여름밤", 8);
        when(repository.ensureAndLock(any(), any())).thenReturn(new LockedNicknameState(state, true));
        when(participants.updateNickname(17L, "여름밤")).thenReturn(new UpdateNicknameResponse(17L, 2));

        var result = service.applyVersionedNickname(command);

        assertThat(result.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        assertThat(result.updatedParticipantCount()).isEqualTo(2);
        assertThat(state.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 12, 0));
        verify(participants).updateNickname(17L, "여름밤");
    }

    /** 중복과 과거 입력은 이름·버전·시각을 변경하거나 참가자를 다시 갱신하지 않는다. */
    @ParameterizedTest
    @CsvSource({"여름밤,8,DUPLICATE", "봄바람,7,STALE"})
    void duplicateAndStaleMessagesDoNotChangeState(
            String nickname, long version, ChatNicknameVersionResult.Status expected) {
        when(repository.ensureAndLock(any(), any())).thenReturn(new LockedNicknameState(state, false));

        var result = service.applyVersionedNickname(command(nickname, version));

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.updatedParticipantCount()).isZero();
        assertThat(state.getNickname()).isEqualTo("여름밤");
        assertThat(state.getNicknameVersion()).isEqualTo(8L);
        assertThat(state.getUpdatedAt()).isEqualTo(PREVIOUS_TIME);
        verifyNoInteractions(participants);
    }

    /** 전체값을 가진 새 버전은 같은 이름이나 중간 버전 누락 여부와 관계없이 전진한다. */
    @ParameterizedTest
    @CsvSource({"가을밤,9", "여름밤,9", "겨울밤,12"})
    void newerMessagesAdvanceStateAndReconcileParticipants(String nickname, long version) {
        when(repository.ensureAndLock(any(), any())).thenReturn(new LockedNicknameState(state, false));
        when(participants.updateNickname(17L, nickname)).thenReturn(new UpdateNicknameResponse(17L, 0));

        var result = service.applyVersionedNickname(command(nickname, version));

        assertThat(result.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        assertThat(state.getNickname()).isEqualTo(nickname);
        assertThat(state.getNicknameVersion()).isEqualTo(version);
        assertThat(state.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 9, 12, 0));
        verify(participants).updateNickname(17L, nickname);
    }

    /** 동일 버전의 다른 이름은 한 번 기록하고 상태를 보존한 채 예외를 호출자에게 준다. */
    @Test
    void conflictingNicknameIsLoggedOnceAndRejected(CapturedOutput output) {
        var command = command("가을밤", 8);
        when(repository.ensureAndLock(any(), any())).thenReturn(new LockedNicknameState(state, false));

        assertThatThrownBy(() -> service.applyVersionedNickname(command))
                .isInstanceOfSatisfying(ChatNicknameVersionConflictException.class, exception -> {
                    assertThat(exception.userId()).isEqualTo(17L);
                    assertThat(exception.nicknameVersion()).isEqualTo(8L);
                    assertThat(exception.eventId()).isEqualTo(command.eventId());
                });

        assertThat(state.getNickname()).isEqualTo("여름밤");
        assertThat(state.getNicknameVersion()).isEqualTo(8L);
        assertThat(state.getUpdatedAt()).isEqualTo(PREVIOUS_TIME);
        assertThat(output.getOut()).contains("userId=17", "nicknameVersion=8", command.eventId().toString());
        assertThat(output.getOut().lines().filter(line -> line.contains(command.eventId().toString())).count())
                .isEqualTo(1L);
        verifyNoInteractions(participants);
    }

    /** 서로 다른 이벤트 ID를 가진 버전 입력을 만든다. */
    private ChatNicknameVersionCommand command(String nickname, long version) {
        return new ChatNicknameVersionCommand(17L, nickname, version, UUID.randomUUID());
    }
}
