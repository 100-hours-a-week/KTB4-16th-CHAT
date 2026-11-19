package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"chat.nickname-state.enabled=true", "spring.datasource.hikari.maximum-pool-size=8"})
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:(?!0*3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupIndependentUserLockIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userA = IDS.incrementAndGet();
    private final long userB = IDS.incrementAndGet();
    private final long questionA = IDS.incrementAndGet();
    private final long questionOldB = IDS.incrementAndGet();
    private final long questionNewB = IDS.incrementAndGet();
    private final LocalDate today = ChatTime.now().toLocalDate();
    private final LocalDate yesterday = today.minusDays(1);

    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    /** 다른 사용자의 참가자 행 잠금 중 별도 그룹 입장의 최초 상태 보정을 관찰한다. */
    @Test
    void observesIndependentUserLockDuringGroupReconciliation() throws Exception {
        exercise(false);
    }

    /** 다른 사용자의 참가자 행 잠금 중 닉네임 이벤트의 참가자 갱신을 관찰한다. */
    @Test
    void observesIndependentUserLockDuringNicknameEvent() throws Exception {
        exercise(true);
    }

    /** 각 역할을 독립 연결로 실행하고 원래 결과와 실제 대기 관계를 수집한다. */
    private void exercise(boolean event) throws Exception {
        assertThat(properties.enabled()).isTrue();
        createFixtures();
        System.out.println("V14 mode=" + (event ? "event" : "group") + ", ON=true, version=8, poolMax=8, db="
                + jdbc.queryForMap("SELECT VERSION() AS version, @@transaction_isolation AS isolation_level"));
        System.out.println("참가자 user_id 잠금 조회 EXPLAIN=" + jdbc.queryForList("""
                EXPLAIN SELECT * FROM chat_room_participants
                WHERE user_id=? ORDER BY chat_room_participant_id FOR UPDATE
                """, userB));
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            long blockerId = connectionId(blocker);
            try (var statement = blocker.prepareStatement("SELECT chat_room_participant_id FROM chat_room_participants WHERE group_date=? AND user_id=? FOR UPDATE")) {
                statement.setObject(1, yesterday);
                statement.setLong(2, userA);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            Future<?> work = executor.submit(() -> {
                if (event) {
                    return events.applyVersionedNickname(new ChatNicknameVersionCommand(userB, "새이름", 8L, UUID.randomUUID()));
                }
                return groups.joinOrCreate(new GroupChatJoinOrCreateRequest(today, questionNewB, userB, "새이름", "A", 8L));
            });
            try {
                var observed = observeParticipantWait(blockerId, work);
                boolean completedWhileBlocked = work.isDone();
                System.out.println("V14 participant wait observed=" + !observed.isEmpty() + ", work completed="
                        + completedWhileBlocked + ", blockerConnection=" + blockerId + ", edges=" + observed);
                assertThat(!observed.isEmpty() || completedWhileBlocked)
                        .as("관찰 시간 초과는 잠금 대기 부재의 증거가 아니다").isTrue();
            } finally {
                blocker.commit();
            }
            try {
                Object result = work.get(20, TimeUnit.SECONDS);
                System.out.println("V14 original outcome=" + result);
                if (event) {
                    assertThat(((ChatNicknameVersionResult) result).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
                } else {
                    assertThat(((GroupChatRoomJoinService.Result) result).created()).isTrue();
                }
            } catch (ExecutionException failure) {
                logFailureAndSnapshot(failure);
                throw failure;
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertSnapshots(event);
    }

    /** 만료된 서로 다른 그룹에 참가자를 만들고 A의 기존 닉네임 상태를 준비한다. */
    private void createFixtures() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertExpiredParticipant(questionA, userA, "기존에이");
            insertExpiredParticipant(questionOldB, userB, "기존비");
            jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '기존에이', 3, NOW())", userA);
        });
    }

    /** 방마다 서로 다른 allocator 키를 사용하며 과거 참가자만 하나씩 기록한다. */
    private void insertExpiredParticipant(long questionId, long userId, String nickname) {
        jdbc.update("""
                INSERT INTO chat_rooms (room_type,group_date,vote_question_id,room_sequence,capacity,admitted_participant_count,expires_at)
                VALUES ('GROUP',?,?,1,30,1,?)
                """, yesterday, questionId, today.atStartOfDay());
        long roomId = jdbc.queryForObject("SELECT chat_room_id FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Long.class, yesterday, questionId);
        jdbc.update("INSERT INTO chat_room_participants (chat_room_id,user_id,nickname,group_date,vote_choice) VALUES (?,?,?,?, 'A')", roomId, userId, nickname, yesterday);
    }

    /** 잠금 증거가 보이거나 작업이 완료될 때까지 유한하게 관찰하며 미관측을 실패로 판단하지 않는다. */
    private List<Map<String, Object>> observeParticipantWait(long blockerId, Future<?> work) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            var waits = jdbc.queryForList("""
                    SELECT rt.PROCESSLIST_ID AS requester, bt.PROCESSLIST_ID AS blocker,
                           r.OBJECT_NAME AS table_name, r.INDEX_NAME AS index_name,
                           r.LOCK_DATA AS requested_row, r.LOCK_MODE AS requested_mode,
                           b.INDEX_NAME AS blocking_index, b.LOCK_DATA AS blocking_row,
                           b.LOCK_MODE AS blocking_mode, rt.PROCESSLIST_INFO AS requested_sql
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks r ON w.REQUESTING_ENGINE_LOCK_ID=r.ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks b ON w.BLOCKING_ENGINE_LOCK_ID=b.ENGINE_LOCK_ID
                    JOIN performance_schema.threads rt ON r.THREAD_ID=rt.THREAD_ID
                    JOIN performance_schema.threads bt ON b.THREAD_ID=bt.THREAD_ID
                    WHERE r.OBJECT_SCHEMA=DATABASE() AND r.OBJECT_NAME='chat_room_participants'
                      AND bt.PROCESSLIST_ID=?
                    """, blockerId);
            if (!waits.isEmpty()) return waits;
            if (work.isDone()) return List.of();
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return List.of();
    }

    /** 예외 원인에서 실제 SQL 오류와 롤백 뒤 상태를 기록하고 재시도하지 않는다. */
    private void logFailureAndSnapshot(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                System.out.println("V14 original SQL error=" + sql.getErrorCode() + ", state=" + sql.getSQLState());
            }
        }
        System.out.println("V14 failure state snapshot=" + jdbc.queryForList("SELECT user_id,nickname,nickname_version FROM chat_user_nickname_states WHERE user_id IN (?,?) ORDER BY user_id", userA, userB));
        System.out.println("V14 failure participant snapshot=" + jdbc.queryForList("SELECT user_id,group_date,nickname FROM chat_room_participants WHERE user_id IN (?,?) ORDER BY user_id,group_date", userA, userB));
        System.out.println("V14 failure room snapshot=" + jdbc.queryForList("SELECT group_date,vote_question_id,admitted_participant_count FROM chat_rooms WHERE vote_question_id IN (?,?,?)", questionA, questionOldB, questionNewB));
        System.out.println("V14 failure allocator snapshot=" + jdbc.queryForList("SELECT group_date,vote_question_id,last_room_sequence FROM chat_room_group_allocators WHERE vote_question_id IN (?,?,?)", questionA, questionOldB, questionNewB));
    }

    /** 커밋 후 A는 그대로이고 B의 상태와 모든 참가자 이름만 갱신됐는지 확인한다. */
    private void assertSnapshots(boolean event) {
        var states = jdbc.queryForList("SELECT user_id,nickname,nickname_version FROM chat_user_nickname_states WHERE user_id IN (?,?) ORDER BY user_id", userA, userB);
        var participants = jdbc.queryForList("SELECT user_id,group_date,nickname FROM chat_room_participants WHERE user_id IN (?,?) ORDER BY user_id,group_date", userA, userB);
        System.out.println("V14 committed states=" + states + ", participants=" + participants);
        assertThat(states).hasSize(2);
        assertThat(states.get(0).get("nickname")).isEqualTo("기존에이");
        assertThat(((Number) states.get(0).get("nickname_version")).longValue()).isEqualTo(3);
        assertThat(states.get(1).get("nickname")).isEqualTo("새이름");
        assertThat(((Number) states.get(1).get("nickname_version")).longValue()).isEqualTo(8);
        assertThat(participants).hasSize(event ? 2 : 3);
        assertThat(participants.get(0).get("nickname")).isEqualTo("기존에이");
        assertThat(participants.subList(1, participants.size())).allSatisfy(row -> assertThat(row.get("nickname")).isEqualTo("새이름"));
    }

    /** 잠금 보유 JDBC 연결의 MySQL 식별자를 얻는다. */
    private long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** 이 테스트의 사용자와 질문에 해당하는 데이터만 정리한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE p FROM chat_room_participants p JOIN chat_rooms r ON p.chat_room_id=r.chat_room_id WHERE r.vote_question_id IN (?,?,?)", questionA, questionOldB, questionNewB);
        jdbc.update("DELETE FROM chat_rooms WHERE vote_question_id IN (?,?,?)", questionA, questionOldB, questionNewB);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE vote_question_id IN (?,?,?)", questionA, questionOldB, questionNewB);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id IN (?,?)", userA, userB);
    }

    /** 비3306 루프백의 전용 그룹 테스트 DB에만 연결한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
