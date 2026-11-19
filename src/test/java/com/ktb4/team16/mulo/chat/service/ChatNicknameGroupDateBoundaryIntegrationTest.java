package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.delivery.ChatDeliveryPublisher;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatParticipantNicknameChangedResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.event.ChatParticipantNicknameChangedDeliveryEvent;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "chat.nickname-state.enabled=true")
@Import(ChatNicknameGroupDateBoundaryIntegrationTest.ClockConfiguration.class)
@RecordApplicationEvents
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches =
        "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):(?!3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupDateBoundaryIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 11);
    private static final Instant BEFORE_MIDNIGHT = DATE.plusDays(1).atStartOfDay(ChatTime.KST)
            .toInstant().minusSeconds(1);
    private final long userId = IDS.incrementAndGet();
    private final long oldQuestionId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();

    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MutableClock clock;
    @Autowired private ApplicationEvents events;
    @MockitoSpyBean private ChatRoomParticipantRepository participants;
    @MockitoBean private ChatDeliveryPublisher publisher;

    /** ON 설정과 이전 GROUP 활성 참가자를 각 시나리오에서 독립적으로 준비한다. */
    @BeforeEach
    void setUp() {
        assertThat(properties.enabled()).isTrue();
        clock.set(BEFORE_MIDNIGHT);
        // 이전 날짜의 그룹도 활성 전달을 관찰하도록 만료 시각을 인위적으로 미래로 둔다.
        jdbc.update("""
                INSERT INTO chat_rooms (room_type, group_date, vote_question_id, room_sequence,
                    capacity, admitted_participant_count, created_at, expires_at)
                VALUES ('GROUP', ?, ?, 1, 30, 1, ?, ?)
                """, DATE.minusDays(1), oldQuestionId, DATE.minusDays(1).atStartOfDay(),
                DATE.plusDays(1).atStartOfDay());
        jdbc.update("""
                INSERT INTO chat_room_participants (chat_room_id, user_id, nickname, group_date,
                    vote_choice, joined_at, left_at)
                SELECT chat_room_id, ?, '이전이름', ?, 'A', ?, NULL FROM chat_rooms
                WHERE group_date=? AND vote_question_id=?
                """, userId, DATE.minusDays(1), DATE.minusDays(1).atStartOfDay(),
                DATE.minusDays(1), oldQuestionId);
    }

    /** 정상 커밋에서 실제 이벤트 등록과 shared publisher 전달 경로가 동작함을 확인한다. */
    @Test
    void committedReconciliationReachesSharedPublisher() {
        groups.joinOrCreate(request());

        assertThat(eventCount()).isEqualTo(1);
        verify(publisher).publish(any(ChatParticipantNicknameChangedResponse.class), any());
        assertThat(oldNickname()).isEqualTo("새이름");
        assertThat(count("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_room_participants WHERE user_id=? AND group_date=?", userId, DATE)).isEqualTo(1);
    }

    /** 첫 상태와 기존 이름을 변경한 다음 참가자 저장이 실패하면 모든 변경과 전달이 롤백된다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void participantSaveFailureRollsBackReconciliationAndAllocation(boolean existingRoom) {
        if (existingRoom) seedTargetRoom();
        doAnswer(invocation -> {
            assertInFlightReconciliation(false);
            throw new DataIntegrityViolationException("injected participant save failure");
        }).when(participants).saveAndFlush(any(ChatRoomParticipant.class));

        assertThatThrownBy(() -> groups.joinOrCreate(request()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("injected participant save failure");

        assertRolledBack(existingRoom);
    }

    /** 실제 참가자 flush 직후 KST 날짜가 바뀌어도 이름·정원·배정이 모두 롤백된다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void midnightAfterParticipantFlushRollsBackReconciliationAndAllocation(boolean existingRoom) {
        if (existingRoom) seedTargetRoom();
        doAnswer(invocation -> {
            // 중요: Spring Data 인터페이스의 추상 메서드 대신 스파이가 감싼 실제 저장소 프록시에 위임한다.
            Object saved = mockingDetails(participants).getMockCreationSettings()
                    .getDefaultAnswer().answer(invocation);
            assertInFlightReconciliation(true);
            clock.set(BEFORE_MIDNIGHT.plusSeconds(1));
            return saved;
        }).when(participants).saveAndFlush(any(ChatRoomParticipant.class));

        assertThatThrownBy(() -> groups.joinOrCreate(request()))
                .isInstanceOfSatisfying(ChatApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ChatErrorCode.INVALID_INPUT_VALUE));

        assertRolledBack(existingRoom);
    }

    /** 실제 상태 행 잠금 대기 중 날짜가 바뀌면 기존 상태와 모든 배정이 유지된다. */
    @Test
    void midnightWhileWaitingForNicknameStateLockRejectsAndRollsBack() throws Exception {
        jdbc.update("""
                INSERT INTO chat_user_nickname_states (user_id, nickname, nickname_version, updated_at)
                VALUES (?, '저장이름', 8, ?)
                """, userId, DATE.atStartOfDay());

        rejectAfterLockWait("chat_user_nickname_states",
                "SELECT user_id FROM chat_user_nickname_states WHERE user_id=? FOR UPDATE", userId);

        assertWaitRolledBack(true, false);
    }

    /** 실제 allocator 잠금 대기 중 날짜가 바뀌면 임시 상태와 방 정원이 원복된다. */
    @Test
    void midnightWhileWaitingForAllocatorLockRejectsAndRollsBack() throws Exception {
        seedTargetRoom();

        rejectAfterLockWait("chat_room_group_allocators", """
                SELECT chat_room_group_allocator_id FROM chat_room_group_allocators
                WHERE group_date=? AND vote_question_id=? FOR UPDATE
                """, DATE, questionId);

        assertWaitRolledBack(false, true);
    }

    /** 독립 연결이 행을 잠근 상태에서 실제 DB 대기를 관측한 후 날짜를 넘기고 결과를 확인한다. */
    private void rejectAfterLockWait(String table, String lockSql, Object... lockParameters) throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        var pool = (HikariDataSource) dataSource;
        // 잠금 보유자, 서비스 요청, 잠금 관측자에 각각 연결이 필요하다.
        assertThat(pool.getMaximumPoolSize()).isGreaterThanOrEqualTo(3);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                long blockerId = connectionId(blocker);
                try (PreparedStatement statement = blocker.prepareStatement(lockSql)) {
                    for (int i = 0; i < lockParameters.length; i++) {
                        statement.setObject(i + 1, lockParameters[i]);
                    }
                    try (ResultSet rows = statement.executeQuery()) {
                        assertThat(rows.next()).as("잠글 fixture 행").isTrue();
                    }
                }
                Future<GroupChatRoomJoinService.Result> result = executor.submit(() -> groups.joinOrCreate(request()));
                awaitDatabaseLock(table, blockerId, result);
                clock.set(BEFORE_MIDNIGHT.plusSeconds(1));
                blocker.commit();

                assertThatThrownBy(() -> result.get(20, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ChatApiException.class)
                        .satisfies(error -> assertThat(((ChatApiException) error.getCause()).getErrorCode())
                                .isEqualTo(ChatErrorCode.INVALID_INPUT_VALUE));
            } finally {
                // 실패한 관측이나 timeout에서도 서비스가 잠금 해제를 기다리지 않도록 한다.
                blocker.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).as("입장 스레드 종료").isTrue();
        }
    }

    /** 잠금 보유 연결의 MySQL 연결 ID를 구해 다른 연결의 대기 관계와 대조한다. */
    private long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    /** performance_schema에서 지정한 연결과 테이블의 실제 InnoDB 잠금 대기 edge를 확인한다. */
    private void awaitDatabaseLock(String table, long blockerId,
            Future<GroupChatRoomJoinService.Result> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> waits = jdbc.queryForList("""
                    SELECT waiting.OBJECT_NAME AS table_name, waiting.INDEX_NAME AS index_name,
                           waiting.LOCK_MODE AS waiting_mode, waiter.PROCESSLIST_ID AS waiter_id,
                           blocker.PROCESSLIST_ID AS blocker_id
                    FROM performance_schema.data_lock_waits edge
                    JOIN performance_schema.data_locks waiting
                      ON waiting.ENGINE=edge.ENGINE AND waiting.ENGINE_LOCK_ID=edge.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks held
                      ON held.ENGINE=edge.ENGINE AND held.ENGINE_LOCK_ID=edge.BLOCKING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads waiter ON waiter.THREAD_ID=waiting.THREAD_ID
                    JOIN performance_schema.threads blocker ON blocker.THREAD_ID=held.THREAD_ID
                    WHERE waiting.OBJECT_SCHEMA=DATABASE() AND waiting.OBJECT_NAME=?
                      AND blocker.PROCESSLIST_ID=?
                    """, table, blockerId);
            if (!waits.isEmpty()) {
                System.out.println("실제 DB 잠금 대기: " + waits);
                return;
            }
            if (result.isDone()) {
                throw new AssertionError("잠금 대기 관측 전 그룹 요청이 종료됨: " + result.get());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("실제 DB 잠금 대기 관측 시간 초과: " + table + ", blocker=" + blockerId);
    }

    /** 대기 이후 거절된 요청의 DB 스냅샷과 커밋 후 닉네임 미전달을 확인한다. */
    private void assertWaitRolledBack(boolean existingState, boolean existingRoom) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(publisher, never()).publish(any(), any());
        assertThat(oldNickname()).isEqualTo("이전이름");
        assertThat(count("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", userId))
                .isEqualTo(existingState ? 1 : 0);
        if (existingState) {
            assertThat(jdbc.queryForObject("SELECT nickname FROM chat_user_nickname_states WHERE user_id=?",
                    String.class, userId)).isEqualTo("저장이름");
            assertThat(count("SELECT nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId))
                    .isEqualTo(8);
        }
        assertThat(count("SELECT COUNT(*) FROM chat_room_participants WHERE user_id=? AND group_date=?", userId, DATE))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", DATE, questionId))
                .isEqualTo(existingRoom ? 1 : 0);
        assertThat(count("SELECT COUNT(*) FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", DATE, questionId))
                .isEqualTo(existingRoom ? 1 : 0);
        if (existingRoom) {
            assertThat(count("SELECT admitted_participant_count FROM chat_rooms WHERE group_date=? AND vote_question_id=?", DATE, questionId)).isEqualTo(5);
            assertThat(count("SELECT last_room_sequence FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", DATE, questionId)).isEqualTo(1);
        }
    }

    /** 기존 방 재사용 시에도 변경 전의 방 정원과 allocator가 보존되는지 비교할 fixture를 만든다. */
    private void seedTargetRoom() {
        jdbc.update("""
                INSERT INTO chat_room_group_allocators (group_date, vote_question_id, last_room_sequence)
                VALUES (?, ?, 1)
                """, DATE, questionId);
        jdbc.update("""
                INSERT INTO chat_rooms (room_type, group_date, vote_question_id, room_sequence,
                    capacity, admitted_participant_count, created_at, expires_at)
                VALUES ('GROUP', ?, ?, 1, 30, 5, ?, ?)
                """, DATE, questionId, DATE.atStartOfDay(), DATE.plusDays(1).atStartOfDay());
    }

    /** 스파이 지점에서 실제 트랜잭션과 상태·기존 이름·이벤트 등록이 선행됐음을 확인한다. */
    private void assertInFlightReconciliation(boolean participantFlushed) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", userId)).isEqualTo(1);
        assertThat(oldNickname()).isEqualTo("새이름");
        assertThat(eventCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", DATE, questionId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_room_participants WHERE user_id=? AND group_date=?", userId, DATE))
                .isEqualTo(participantFlushed ? 1 : 0);
    }

    /** 트랜잭션 종료 후 새 연결의 DB 결과와 커밋 후 전달 부재를 검사한다. */
    private void assertRolledBack(boolean existingRoom) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(eventCount()).isEqualTo(1);
        verify(publisher, never()).publish(any(), any());
        assertThat(oldNickname()).isEqualTo("이전이름");
        assertThat(count("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", userId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_room_participants WHERE user_id=? AND group_date=?", userId, DATE)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", DATE, questionId))
                .isEqualTo(existingRoom ? 1 : 0);
        assertThat(count("SELECT COUNT(*) FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", DATE, questionId))
                .isEqualTo(existingRoom ? 1 : 0);
        if (existingRoom) {
            assertThat(count("SELECT admitted_participant_count FROM chat_rooms WHERE group_date=? AND vote_question_id=?", DATE, questionId)).isEqualTo(5);
            assertThat(count("SELECT last_room_sequence FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", DATE, questionId)).isEqualTo(1);
        }
    }

    /** 기존 GROUP 참가자의 현재 이름을 읽는다. */
    private String oldNickname() {
        return jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE user_id=? AND group_date=?",
                String.class, userId, DATE.minusDays(1));
    }

    /** SQL 결과의 단일 정수 값을 조회한다. */
    private int count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    /** 실제 애플리케이션 이벤트 등록 횟수를 반환한다. */
    private long eventCount() {
        return events.stream(ChatParticipantNicknameChangedDeliveryEvent.class).count();
    }

    /** 상태 최초 생성과 기존 참가자 보정을 유발하는 버전 요청을 만든다. */
    private GroupChatJoinOrCreateRequest request() {
        return new GroupChatJoinOrCreateRequest(DATE, questionId, userId, "새이름", "A", 8L);
    }

    /** 이 시나리오의 사용자와 그룹 행만 삭제한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM chat_room_participants WHERE user_id=?", userId);
        jdbc.update("DELETE FROM chat_rooms WHERE vote_question_id IN (?, ?)", oldQuestionId, questionId);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE vote_question_id IN (?, ?)", oldQuestionId, questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    /** 전용 루프백 그룹 DB 연결을 테스트 애플리케이션에 적용한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        /** 서비스들이 공유하는 가변 테스트 시계를 기본 시계로 제공한다. */
        @Bean
        @Primary
        MutableClock mutableClock() { return new MutableClock(new AtomicReference<>(BEFORE_MIDNIGHT), ZoneOffset.UTC); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        /** 모든 시간대의 시계가 같은 제어 시점을 공유하도록 구성한다. */
        MutableClock(AtomicReference<Instant> instant, ZoneId zone) { this.instant = instant; this.zone = zone; }
        /** 트랜잭션 내부에서 현재 시점을 전환한다. */
        void set(Instant value) { instant.set(value); }
        /** 시계의 시간대를 반환한다. */
        @Override public ZoneId getZone() { return zone; }
        /** 같은 제어 시점을 유지하는 다른 시간대의 시계를 만든다. */
        @Override public Clock withZone(ZoneId newZone) { return new MutableClock(instant, newZone); }
        /** 현재 제어 시점을 반환한다. */
        @Override public Instant instant() { return instant.get(); }
    }
}
