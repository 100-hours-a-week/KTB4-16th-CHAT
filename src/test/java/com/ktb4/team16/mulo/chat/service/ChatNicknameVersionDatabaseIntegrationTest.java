package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatNicknameVersionConflictException;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
class ChatNicknameVersionDatabaseIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    @Autowired private ChatNicknameVersionService service;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private ChatRoomParticipantRepository participants;
    @MockitoSpyBean private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private SimpMessagingTemplate messaging;
    @MockitoSpyBean private ChatParticipantNicknameService legacyService;
    private long userId;
    private Long roomId;

    /** 이번 테스트만 사용하는 사용자 식별자를 만든다. */
    @BeforeEach
    void setUp() {
        userId = IDS.incrementAndGet();
    }

    /** 직접 커밋한 테스트 상태와 채팅방만 삭제한다. */
    @AfterEach
    void cleanUp() {
        if (roomId != null) {
            jdbc.update("DELETE FROM chat_room_participants WHERE chat_room_id = ?", roomId);
            jdbc.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", roomId);
        }
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id = ?", userId);
    }

    /** 첫 이벤트를 적용한 뒤 늦은 입력과 중복은 DB와 화면에 재적용하지 않는다. */
    @Test
    void firstMessageUpdatesParticipantsAndDuplicateOrStaleMessagesAreSkipped() {
        createParticipant("봄바람");
        var first = service.applyVersionedNickname(command("여름밤", 8));
        assertThat(first.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        assertThat(first.updatedParticipantCount()).isEqualTo(1);
        assertState("여름밤", 8);
        assertParticipant("여름밤");
        var appliedAt = jdbc.queryForObject("""
                SELECT updated_at FROM chat_user_nickname_states WHERE user_id = ?
                """, java.time.LocalDateTime.class, userId);
        clearInvocations(messaging);

        assertThat(service.applyVersionedNickname(command("여름밤", 8)).status())
                .isEqualTo(ChatNicknameVersionResult.Status.DUPLICATE);
        assertThat(service.applyVersionedNickname(command("봄바람", 7)).status())
                .isEqualTo(ChatNicknameVersionResult.Status.STALE);

        assertState("여름밤", 8);
        assertParticipant("여름밤");
        assertThat(jdbc.queryForObject("""
                SELECT updated_at FROM chat_user_nickname_states WHERE user_id = ?
                """, java.time.LocalDateTime.class, userId)).isEqualTo(appliedAt);
        verifyNoInteractions(messaging);
    }

    /** 방 참가 기록이 없더라도 버전 0을 포함한 첫 상태를 기록한다. */
    @ParameterizedTest
    @ValueSource(longs = {0, 8})
    void storesStateWithoutAnyRoom(long version) {
        var result = service.applyVersionedNickname(command("여름밤", version));
        assertThat(result.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        assertThat(result.updatedParticipantCount()).isZero();
        assertState("여름밤", version);
        verifyNoInteractions(messaging);
    }

    /** 같은 이름의 새 버전은 전진하고 서로 다른 참가자 스냅샷도 맞춘다. */
    @Test
    void newerVersionWithSameNameReconcilesParticipants() {
        service.applyVersionedNickname(command("여름밤", 8));
        createParticipant("봄바람");
        var result = service.applyVersionedNickname(command("여름밤", 9));
        assertThat(result.updatedParticipantCount()).isEqualTo(1);
        assertState("여름밤", 9);
        assertParticipant("여름밤");
    }

    /** 같은 버전의 다른 이름은 대소문자 차이도 구분해 거절한다. */
    @Test
    void conflictingVersionPreservesStateAndParticipant() {
        createParticipant("봄바람");
        service.applyVersionedNickname(command("Mulo", 8));
        clearInvocations(messaging);

        assertThatThrownBy(() -> service.applyVersionedNickname(command("mulo", 8)))
                .isInstanceOf(ChatNicknameVersionConflictException.class);

        assertState("Mulo", 8);
        assertParticipant("Mulo");
        verifyNoInteractions(messaging);
    }

    /** 실제 INSERT·참가자 flush가 성공했어도 상위 롤백은 둘 다 취소한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outerRollbackRestoresStateAndParticipant(boolean existingState) {
        createParticipant("봄바람");
        if (existingState) {
            service.applyVersionedNickname(command("봄바람", 7));
        }
        clearInvocations(messaging);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.applyVersionedNickname(command("여름밤", 8));
            entityManager.flush();
            assertState("여름밤", 8);
            assertParticipant("여름밤");
            status.setRollbackOnly();
        });

        if (existingState) {
            assertState("봄바람", 7);
        } else {
            assertThat(stateCount()).isZero();
        }
        assertParticipant("봄바람");
        verifyNoInteractions(messaging);
    }

    /** 기존 참가자 처리 중 오류가 생기면 사용자 상태까지 함께 롤백한다. */
    @Test
    void participantFailureRollsBackAlreadyFlushedState() {
        createParticipant("봄바람");
        service.applyVersionedNickname(command("봄바람", 7));
        clearInvocations(messaging);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new DataIntegrityViolationException("participant fixture failure");
        }).when(legacyService).updateNickname(userId, "여름밤");

        assertThatThrownBy(() -> service.applyVersionedNickname(command("여름밤", 8)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertState("봄바람", 7);
        assertParticipant("봄바람");
        verifyNoInteractions(messaging);
    }

    /** 최초 생성과 기존 상태 각각에서 도착 순서가 바뀌어도 큰 버전을 보존한다. */
    @ParameterizedTest
    @CsvSource({"false,8,9", "false,9,8", "true,8,9", "true,9,8"})
    void concurrentMessagesKeepTheNewestVersion(boolean existingState, long first, long second)
            throws Exception {
        createParticipant("봄바람");
        if (existingState) {
            service.applyVersionedNickname(command("봄바람", 7));
        }
        String firstName = first == 8 ? "여름밤" : "가을밤";
        String secondName = second == 8 ? "여름밤" : "가을밤";
        var future = runCompeting(command(firstName, first), command(secondName, second));
        var result = future.get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(second > first
                ? ChatNicknameVersionResult.Status.APPLIED : ChatNicknameVersionResult.Status.STALE);
        assertState("가을밤", 9);
        assertParticipant("가을밤");
        assertThat(stateCount()).isEqualTo(1);
    }

    /** 동시에 온 같은 버전의 다른 이름은 한 쪽만 반영한다. */
    @Test
    void concurrentConflictingFirstMessagesRejectTheSecond() throws Exception {
        createParticipant("봄바람");
        var future = runCompeting(command("여름밤", 8), command("가을밤", 8));
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ChatNicknameVersionConflictException.class);
        assertState("여름밤", 8);
        assertParticipant("여름밤");
    }

    /** 중복 INSERT 직후 두 요청이 겹쳐도 최신값으로 수렴하고 deadlock은 재처리한다. */
    @Test
    @SuppressWarnings("unchecked")
    void overlappingDuplicateInsertsConvergeToTheNewestVersion() throws Exception {
        createParticipant("봄바람");
        service.applyVersionedNickname(command("봄바람", 7));
        var eighth = command("여름밤", 8);
        var ninth = command("가을밤", 9);
        CyclicBarrier inserted = new CyclicBarrier(2);
        AtomicInteger overlaps = new AtomicInteger();
        // 실제 JDBC INSERT를 실행한 뒤 두 트랜잭션의 SELECT FOR UPDATE 시점만 맞춘다.
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            overlaps.incrementAndGet();
            inserted.await(5, TimeUnit.SECONDS);
            return result;
        }).when(jdbc).execute(any(ConnectionCallback.class));
        ChatNicknameVersionCommand failed = null;
        int deadlocks = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var commands = java.util.List.of(eighth, ninth);
            var futures = commands.stream()
                    .map(value -> executor.submit(() -> service.applyVersionedNickname(value)))
                    .toList();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    assertThat(futures.get(index).get(10, TimeUnit.SECONDS).status())
                            .isIn(ChatNicknameVersionResult.Status.APPLIED, ChatNicknameVersionResult.Status.STALE);
                } catch (ExecutionException exception) {
                    Throwable root = exception.getCause();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOfSatisfying(java.sql.SQLException.class,
                            sql -> assertThat(sql.getErrorCode()).isEqualTo(1213));
                    failed = commands.get(index);
                    deadlocks++;
                }
            }
        } finally {
            doCallRealMethod().when(jdbc).execute(any(ConnectionCallback.class));
        }
        assertThat(overlaps.get()).isEqualTo(2);
        assertThat(deadlocks).isLessThanOrEqualTo(1);
        if (failed != null) {
            service.applyVersionedNickname(failed);
        }
        assertState("가을밤", 9);
        assertParticipant("가을밤");
        assertThat(stateCount()).isEqualTo(1);
    }

    /** 서비스 프록시 경계에서 잘못된 입력을 DB 접근 전에 거절한다. */
    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidInputIsRejectedBeforeDatabaseWrites(ChatNicknameVersionCommand command) {
        assertThatThrownBy(() -> service.applyVersionedNickname(command))
                .isInstanceOf(ConstraintViolationException.class);
        assertThat(stateCount()).isZero();
        verifyNoInteractions(messaging);
        verify(legacyService, never()).updateNickname(any(), any());
    }

    /** 기존 HTTP에서 사용하는 함수는 버전 상태를 임의로 생성하지 않는다. */
    @Test
    void legacyNicknameUpdateDoesNotCreateVersionState() {
        createParticipant("봄바람");
        legacyService.updateNickname(userId, "여름밤");
        assertParticipant("여름밤");
        assertThat(stateCount()).isZero();
    }

    /** 입력 계약의 각 필수값과 경계를 개별적으로 위반하는 자료를 만든다. */
    private static Stream<Arguments> invalidCommands() {
        UUID eventId = UUID.randomUUID();
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new ChatNicknameVersionCommand(null, "봄", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(0L, "봄", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(-1L, "봄", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, null, 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, "", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, " ", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, "12345678901", 1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, "봄", -1, eventId)),
                Arguments.of(new ChatNicknameVersionCommand(17L, "봄", 1, null)));
    }

    /** 첫 변경을 커밋하지 않은 동안 둘째 서비스 호출이 같은 사용자 잠금을 기다리게 한다. */
    private Future<ChatNicknameVersionResult> runCompeting(
            ChatNicknameVersionCommand first, ChatNicknameVersionCommand second) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<ChatNicknameVersionResult> future = new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        service.applyVersionedNickname(first);
                        var pending = executor.submit(() -> {
                            started.countDown();
                            return service.applyVersionedNickname(second);
                        });
                        try {
                            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                        return pending;
                    });
            return future;
        }
    }

    /** 유효한 채팅방과 입장한 참가자 한 명을 커밋한다. */
    private void createParticipant(String nickname) {
        var room = rooms.saveAndFlush(ChatRoom.createGroup(
                LocalDate.of(2026, 10, 14), IDS.incrementAndGet(), 1, OffsetDateTime.now(ChatTime.KST).plusDays(1)));
        roomId = room.getChatRoomId();
        var participant = ChatRoomParticipant.create(room, userId, nickname);
        participant.enter();
        participants.saveAndFlush(participant);
    }

    /** 이번 사용자의 입력 메시지를 새 이벤트 ID로 만든다. */
    private ChatNicknameVersionCommand command(String nickname, long version) {
        return new ChatNicknameVersionCommand(userId, nickname, version, UUID.randomUUID());
    }

    /** 실제 DB의 사용자 이름과 버전을 확인한다. */
    private void assertState(String nickname, long version) {
        assertThat(jdbc.queryForList("""
                SELECT nickname, nickname_version FROM chat_user_nickname_states WHERE user_id = ?
                """, userId)).singleElement().satisfies(row -> {
                    assertThat(row.get("nickname")).isEqualTo(nickname);
                    assertThat(row.get("nickname_version")).isEqualTo(version);
                });
    }

    /** 현재 채팅방에서 보이는 참가자 이름을 실제 DB에서 확인한다. */
    private void assertParticipant(String nickname) {
        assertThat(jdbc.queryForObject("""
                SELECT nickname FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?
                """, String.class, roomId, userId)).isEqualTo(nickname);
    }

    /** 이번 사용자의 상태 행 수를 조회한다. */
    private int stateCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id = ?", Integer.class, userId);
    }
}
