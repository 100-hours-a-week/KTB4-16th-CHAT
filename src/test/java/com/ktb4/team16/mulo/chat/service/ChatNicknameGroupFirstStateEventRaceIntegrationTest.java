package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"chat.nickname-state.enabled=true", "spring.datasource.hikari.maximum-pool-size=8"})
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches =
        "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):(?!0*3306/)\\d{2,5}/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupFirstStateEventRaceIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate today = ChatTime.now().toLocalDate();

    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ChatParticipantNicknameService participants;

    /** 최초 이벤트가 상태를 선점한 뒤 입장 8이 기다리고 이벤트 9의 이름을 사용한다. */
    @Test
    void firstEventWinsBeforeGroupJoin() throws Exception {
        exercise(true);
    }

    /** 최초 입장 8이 상태를 선점한 뒤 이벤트 9가 기다리고 참가자 이름을 갱신한다. */
    @Test
    void firstGroupJoinIsFollowedByEvent() throws Exception {
        exercise(false);
    }

    /** 최초 INSERT 후 참가자 보정 직전 멈춰 후행 요청의 PK 대기와 원래 결과를 관찰한다. */
    private void exercise(boolean eventFirst) throws Exception {
        assertThat(properties.enabled()).isTrue();
        seedExpiredParticipant();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?",
                Integer.class, userId)).isZero();
        System.out.println("V7 eventFirst=" + eventFirst + ", ON=true, groupVersion=8, eventVersion=9, poolMax=8, db="
                + jdbc.queryForMap("SELECT VERSION() AS version, @@transaction_isolation AS isolation_level"));

        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstThread = new AtomicReference<Thread>();
        var firstConnection = new AtomicLong();
        doAnswer(invocation -> {
            if (Thread.currentThread() == firstThread.get()) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                // 선행 연결에서 자신의 미커밋 INSERT를 확인한 뒤 같은 연결의 행 잠금을 유지한다.
                var firstState = jdbc.queryForMap("""
                        SELECT nickname, nickname_version, CONNECTION_ID() AS connection_id,
                               @@transaction_isolation AS isolation_level
                        FROM chat_user_nickname_states WHERE user_id=?
                        """, userId);
                assertThat(firstState.get("nickname")).isEqualTo(eventFirst ? "가을밤" : "봄바람");
                assertThat(((Number) firstState.get("nickname_version")).longValue())
                        .isEqualTo(eventFirst ? 9L : 8L);
                firstConnection.set(((Number) firstState.get("connection_id")).longValue());
                System.out.println("V7 first transaction state=" + firstState);
                inserted.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("최초 상태 보정 재개 시간 초과");
                }
            }
            // 중요: 서비스 프록시를 유지하고 원래 참가자 보정 로직을 그대로 실행한다.
            return mockingDetails(participants).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(participants).updateNickname(any(), any());

        var executor = Executors.newFixedThreadPool(2);
        var failures = new ArrayList<Throwable>();
        Object firstResult = null;
        Object secondResult = null;
        boolean observedWait = false;
        try {
            Future<Object> first = executor.submit(() -> {
                firstThread.set(Thread.currentThread());
                return eventFirst ? applyEvent() : joinGroup();
            });
            assertThat(inserted.await(10, TimeUnit.SECONDS)).as("최초 INSERT 완료").isTrue();
            Future<Object> second = executor.submit(() -> eventFirst ? joinGroup() : applyEvent());
            observedWait = awaitStateLock(firstConnection.get(), second);
            release.countDown();
            firstResult = outcome("first", first, failures);
            secondResult = outcome("second", second, failures);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).as("V7 작업 스레드 종료").isTrue();
            reset(participants);
        }

        System.out.println("V7 first=" + firstResult + ", second=" + secondResult + ", failures=" + failures);
        System.out.println("V7 state=" + jdbc.queryForList(
                "SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId));
        System.out.println("V7 participants=" + jdbc.queryForList(
                "SELECT group_date,nickname FROM chat_room_participants WHERE user_id=? ORDER BY group_date", userId));
        if (failures.stream().anyMatch(this::containsDeadlock)) {
            System.out.println("V7 InnoDB deadlock evidence=" + jdbc.queryForList("SHOW ENGINE INNODB STATUS"));
        }
        assertThat(failures).as("원래 요청의 실패; 자동 재시도 없음").isEmpty();
        assertThat(observedWait).as("선행 연결과 사용자 PK에 대한 실제 대기 관측").isTrue();

        var group = (GroupChatRoomJoinService.Result) (eventFirst ? secondResult : firstResult);
        var event = (ChatNicknameVersionResult) (eventFirst ? firstResult : secondResult);
        assertThat(group.created()).isTrue();
        assertThat(event.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        var state = jdbc.queryForMap("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId);
        assertThat(state.get("nickname")).isEqualTo("가을밤");
        assertThat(((Number) state.get("nickname_version")).longValue()).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT nickname FROM chat_room_participants WHERE user_id=? ORDER BY group_date",
                String.class, userId)).containsExactly("가을밤", "가을밤");
        assertThat(jdbc.queryForObject("SELECT admitted_participant_count FROM chat_rooms WHERE chat_room_id=?",
                Integer.class, group.response().chatRoomId())).isEqualTo(1);
    }

    /** 선행 연결과 사용자 PK가 일치하는 실제 InnoDB 대기 edge를 확인한다. */
    private boolean awaitStateLock(long blockerId, Future<?> second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> waits = jdbc.queryForList("""
                    SELECT rt.PROCESSLIST_ID AS requester, bt.PROCESSLIST_ID AS blocker,
                           r.INDEX_NAME AS index_name, r.LOCK_DATA AS lock_data,
                           r.LOCK_MODE AS requested_mode, b.LOCK_MODE AS blocking_mode,
                           rt.PROCESSLIST_INFO AS requested_sql
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks r ON w.REQUESTING_ENGINE_LOCK_ID=r.ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks b ON w.BLOCKING_ENGINE_LOCK_ID=b.ENGINE_LOCK_ID
                    JOIN performance_schema.threads rt ON r.THREAD_ID=rt.THREAD_ID
                    JOIN performance_schema.threads bt ON b.THREAD_ID=bt.THREAD_ID
                    WHERE r.OBJECT_SCHEMA=DATABASE() AND r.OBJECT_NAME='chat_user_nickname_states'
                      AND r.INDEX_NAME='PRIMARY' AND bt.PROCESSLIST_ID=?
                    """, blockerId);
            for (var wait : waits) {
                if (Long.toString(userId).equals(String.valueOf(wait.get("lock_data")))) {
                    System.out.println("V7 actual state lock wait=" + wait);
                    return true;
                }
            }
            if (second.isDone()) {
                System.out.println("V7 후행 요청이 PK 대기 관측 전에 종료됨");
                return false;
            }
            Thread.sleep(50);
        }
        System.out.println("V7 상태 PK 대기 관측 시간 초과: blocker=" + blockerId);
        return false;
    }

    /** 호출별 예외를 보존하며 두 요청을 각각 한 번씩만 수집한다. */
    private Object outcome(String label, Future<Object> future, List<Throwable> failures) throws Exception {
        try {
            Object result = future.get(30, TimeUnit.SECONDS);
            System.out.println("V7 " + label + " result=" + result);
            return result;
        } catch (ExecutionException failure) {
            failures.add(failure.getCause());
            System.out.println("V7 " + label + " failure=" + failure.getCause());
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    System.out.println("V7 " + label + " SQL=" + sql.getErrorCode() + ", state=" + sql.getSQLState());
                }
            }
            return null;
        }
    }

    /** 결과의 원인 체인에서 MySQL 데드락 피해자를 식별한다. */
    private boolean containsDeadlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213) return true;
        }
        return false;
    }

    /** 실제 서비스 프록시로 버전 9의 닉네임 이벤트를 처리한다. */
    private ChatNicknameVersionResult applyEvent() {
        return events.applyVersionedNickname(new ChatNicknameVersionCommand(userId, "가을밤", 9L, UUID.randomUUID()));
    }

    /** 실제 서비스 프록시로 버전 8의 그룹 입장을 처리한다. */
    private GroupChatRoomJoinService.Result joinGroup() {
        return groups.joinOrCreate(new GroupChatJoinOrCreateRequest(today, questionId, userId, "봄바람", "A", 8L));
    }

    /** 전날 만료 그룹의 기존 참가자를 준비해 두 경로의 이름 보정도 관찰한다. */
    private void seedExpiredParticipant() {
        LocalDate previous = today.minusDays(1);
        jdbc.update("""
                INSERT INTO chat_rooms (room_type, group_date, vote_question_id, room_sequence,
                    capacity, admitted_participant_count, created_at, expires_at)
                VALUES ('GROUP', ?, ?, 1, 30, 1, ?, ?)
                """, previous, questionId, previous.atStartOfDay(), today.atStartOfDay());
        jdbc.update("""
                INSERT INTO chat_room_participants (chat_room_id, user_id, nickname, group_date, vote_choice)
                SELECT chat_room_id, ?, '이전이름', ?, 'A' FROM chat_rooms
                WHERE group_date=? AND vote_question_id=?
                """, userId, previous, previous, questionId);
    }

    /** 이 테스트에 속한 상태와 그룹 행만 삭제한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM chat_room_participants WHERE user_id=?", userId);
        jdbc.update("DELETE FROM chat_rooms WHERE vote_question_id=?", questionId);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE vote_question_id=?", questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    /** 명시된 비3306 루프백 전용 그룹 DB에 연결한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
