package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.delivery.ChatDeliveryPublisher;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.exception.ChatNicknameVersionConflictException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "chat.nickname-state.enabled=true")
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:(?!0*3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupEventOrderIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.addAndGet(100);
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();

    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ChatDeliveryPublisher publisher;
    @MockitoSpyBean private ChatParticipantNicknameService nicknameParticipants;

    /** 첫 이벤트가 오래된 버전의 그룹 배정보다 먼저 끝나면 저장 상태가 우선한다. */
    @Test
    void committedFirstEventWinsOverOlderGroupRequest() {
        assertThat(properties.enabled()).isTrue();
        long previous = previousExpiredParticipant();
        assertThat(event("가을밤", 9).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        var join = groups.joinOrCreate(request(userId, "봄바람", 7));

        assertStateAndParticipants("가을밤", 9, 2);
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE chat_room_id=? AND user_id=?", String.class, previous, userId)).isEqualTo("가을밤");
        assertThat(join.created()).isTrue();
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    /** 그룹 배정 이후 신규·과거·중복·충돌 이벤트의 상태와 활성방 전달을 구분한다. */
    @Test
    void afterGroupJoinEventAdvancesOnlyOnceAndRejectsConflictingVersion() {
        assertThat(properties.enabled()).isTrue();
        var join = groups.joinOrCreate(request(userId, "여름밤", 8));
        activateRoom(join.response().chatRoomId());
        assertThat(event("가을밤", 9).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        verifyNicknameDelivery(join.response().chatRoomId(), 1);
        reset(publisher);

        assertThat(event("여름밤", 8).status()).isEqualTo(ChatNicknameVersionResult.Status.STALE);
        assertThat(event("가을밤", 9).status()).isEqualTo(ChatNicknameVersionResult.Status.DUPLICATE);
        UUID eventId = UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(ChatNicknameVersionService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> events.applyVersionedNickname(
                    new ChatNicknameVersionCommand(userId, "겨울밤", 9, eventId)))
                    .isInstanceOf(ChatNicknameVersionConflictException.class);
            assertThat(appender.list).anySatisfy(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
                assertThat(entry.getFormattedMessage()).contains("userId=" + userId, "nicknameVersion=9", "eventId=" + eventId);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertStateAndParticipants("가을밤", 9, 1);
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    /** 30명 배정 중 상태 잠금 대기와 커밋 후 활성 참가자 전달을 함께 확인한다. */
    @Test
    void eventWaitingDuringThirtyJoinsPublishesAfterCommit() throws Exception {
        assertThat(properties.enabled()).isTrue();
        var locked = new CountDownLatch(1);
        var releaseJoin = new CountDownLatch(1);
        var eventStarted = new CountDownLatch(1);
        var releaseEvent = new CountDownLatch(1);
        var joinThread = new AtomicReference<Thread>();
        var eventThread = new AtomicReference<Thread>();
        var connectionId = new AtomicLong();
        doAnswer(invocation -> {
            if (Thread.currentThread() == joinThread.get()) {
                connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                locked.countDown();
                if (!releaseJoin.await(50, TimeUnit.SECONDS)) throw new IllegalStateException("입장 잠금 해제 시간 초과");
            } else if (Thread.currentThread() == eventThread.get()) {
                eventStarted.countDown();
                if (!releaseEvent.await(50, TimeUnit.SECONDS)) throw new IllegalStateException("이벤트 잠금 해제 시간 초과");
            }
            return org.mockito.Mockito.mockingDetails(nicknameParticipants).getMockCreationSettings()
                    .getDefaultAnswer().answer(invocation);
        }).when(nicknameParticipants).updateNickname(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var executor = Executors.newFixedThreadPool(31);
        try {
            var target = executor.submit(() -> {
                joinThread.set(Thread.currentThread());
                return groups.joinOrCreate(request(userId, "여름밤", 8));
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var applying = executor.submit(() -> {
                eventThread.set(Thread.currentThread());
                return event("가을밤", 9);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int waits = 0;
            while (System.nanoTime() < deadline) {
                waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON w.BLOCKING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID
                        JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID
                        WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_user_nickname_states'
                          AND t.PROCESSLIST_ID=?
                        """, Integer.class, connectionId.get());
                if (waits > 0) break;
                if (applying.isDone()) break;
                Thread.sleep(50);
            }
            System.out.println("30명 배정 중 이벤트의 상태 PK 잠금 대기=" + waits + ", 보유 연결=" + connectionId.get());
            assertThat(waits).isPositive();
            var otherJoins = java.util.stream.IntStream.range(1, 30)
                    .mapToObj(i -> executor.submit(() -> groups.joinOrCreate(request(userId + i, "여름밤", 8))))
                    .toList();
            releaseJoin.countDown();
            var joined = target.get(30, TimeUnit.SECONDS);
            assertThat(joined.created()).isTrue();
            assertThat(eventStarted.await(20, TimeUnit.SECONDS)).isTrue();
            for (var other : otherJoins) assertThat(other.get(30, TimeUnit.SECONDS).created()).isTrue();
            long roomId = joined.response().chatRoomId();
            assertThat(jdbc.queryForObject("SELECT admitted_participant_count FROM chat_rooms WHERE chat_room_id=?", Integer.class, roomId)).isEqualTo(30);
            activateRoom(roomId);
            releaseEvent.countDown();
            assertThat(applying.get(30, TimeUnit.SECONDS).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
            verify(publisher, times(1)).publish(
                    argThat(message -> message instanceof ChatParticipantNicknameChangedResponse response
                            && response.chatRoomId().equals(roomId) && response.userId().equals(userId)
                            && response.nickname().equals("가을밤")),
                    argThat(recipients -> recipients.size() == 30 && recipients.contains(userId)));
            System.out.println("30명 배정 중 이벤트: 원래 입장 성공=30, 이벤트 성공=1, 닉네임 이벤트 발행=1, 수신자=30");
        } finally {
            releaseJoin.countDown();
            releaseEvent.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            reset(nicknameParticipants);
        }
        assertStateAndParticipants("가을밤", 9, 1);
    }

    /** 30명 배정의 앞 또는 뒤에 이벤트를 처리할 때 각 결과와 활성 수신자 수를 확인한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eventBeforeOrAfterThirtyGroupAssignments(boolean eventBefore) {
        assertThat(properties.enabled()).isTrue();
        if (eventBefore) assertThat(event("가을밤", 9).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        long roomId = 0;
        for (int i = 0; i < 30; i++) {
            var result = groups.joinOrCreate(request(userId + i, "여름밤", 8));
            assertThat(result.created()).isTrue();
            if (i == 0) roomId = result.response().chatRoomId();
            assertThat(result.response().chatRoomId()).isEqualTo(roomId);
        }
        activateRoom(roomId);
        if (!eventBefore) {
            assertThat(event("가을밤", 9).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
            final long expectedRoom = roomId;
            verify(publisher, times(1)).publish(
                    argThat(message -> message instanceof ChatParticipantNicknameChangedResponse response
                            && response.chatRoomId().equals(expectedRoom) && response.userId().equals(userId)
                            && response.nickname().equals("가을밤")),
                    argThat(recipients -> recipients.size() == 30 && recipients.contains(userId)));
        } else {
            verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        }
        assertStateAndParticipants("가을밤", 9, 1);
        assertThat(jdbc.queryForObject("SELECT admitted_participant_count FROM chat_rooms WHERE chat_room_id=?", Integer.class, roomId)).isEqualTo(30);
        System.out.println("30명 배정 이벤트 선행=" + eventBefore + ", 원래 입장 성공=30, 이벤트 성공=1, 닉네임 이벤트 발행=" + (eventBefore ? 0 : 1));
    }

    /** 과거 만료 GROUP의 참가자를 만들고 이벤트의 기존 참가자 보정을 확인한다. */
    private long previousExpiredParticipant() {
        LocalDate previousDate = date.minusDays(1);
        jdbc.update("""
                INSERT INTO chat_rooms (match_id, created_at, expires_at, room_type, group_date, vote_question_id,
                    room_sequence, capacity, admitted_participant_count)
                VALUES (NULL, ?, ?, 'GROUP', ?, ?, 1, 30, 1)
                """, previousDate.atStartOfDay(), date.atStartOfDay(), previousDate, questionId);
        long room = jdbc.queryForObject("SELECT chat_room_id FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Long.class, previousDate, questionId);
        jdbc.update("INSERT INTO chat_room_participants (chat_room_id,user_id,nickname,joined_at,group_date,vote_choice) VALUES (?,?,'봄바람',?,?,'A')",
                room, userId, previousDate.atStartOfDay().plusHours(12), previousDate);
        return room;
    }

    /** 배정만 된 참가자를 실제 닉네임 알림 대상인 활성 참가자로 만든다. */
    private void activateRoom(long roomId) {
        jdbc.update("UPDATE chat_room_participants SET joined_at=NOW(6) WHERE chat_room_id=?", roomId);
    }

    /** 단일 활성 참가자에게 커밋된 변경 이벤트가 전달됐는지 확인한다. */
    private void verifyNicknameDelivery(long roomId, int count) {
        verify(publisher, times(count)).publish(
                argThat(message -> message instanceof ChatParticipantNicknameChangedResponse response
                        && response.chatRoomId().equals(roomId) && response.userId().equals(userId)
                        && response.nickname().equals("가을밤")),
                argThat(recipients -> recipients.size() == 1 && recipients.contains(userId)));
    }

    /** 버전 변경의 원래 호출 결과를 반환한다. */
    private ChatNicknameVersionResult event(String nickname, long version) {
        return events.applyVersionedNickname(new ChatNicknameVersionCommand(userId, nickname, version, UUID.randomUUID()));
    }

    /** 요청에 사용자의 이름과 버전을 명시한다. */
    private GroupChatJoinOrCreateRequest request(long id, String nickname, long version) {
        return new GroupChatJoinOrCreateRequest(date, questionId, id, nickname, "A", version);
    }

    /** 커밋된 상태 및 해당 사용자의 모든 참가자 이름을 별도 조회한다. */
    private void assertStateAndParticipants(String nickname, long version, int count) {
        var state = jdbc.queryForMap("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId);
        assertThat(state.get("nickname")).isEqualTo(nickname);
        assertThat(((Number) state.get("nickname_version")).longValue()).isEqualTo(version);
        assertThat(jdbc.queryForList("SELECT nickname FROM chat_room_participants WHERE user_id=?", String.class, userId))
                .hasSize(count).allMatch(nickname::equals);
    }

    /** 전용 식별자로 생성한 행만 정리한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE p FROM chat_room_participants p JOIN chat_rooms r ON p.chat_room_id=r.chat_room_id WHERE r.vote_question_id=? AND r.group_date IN (?,?)", questionId, date, date.minusDays(1));
        jdbc.update("DELETE FROM chat_rooms WHERE vote_question_id=? AND group_date IN (?,?)", questionId, date, date.minusDays(1));
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE vote_question_id=? AND group_date IN (?,?)", questionId, date, date.minusDays(1));
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id BETWEEN ? AND ?", userId, userId + 29);
    }

    /** 테스트 전용 DB 연결을 사용한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
