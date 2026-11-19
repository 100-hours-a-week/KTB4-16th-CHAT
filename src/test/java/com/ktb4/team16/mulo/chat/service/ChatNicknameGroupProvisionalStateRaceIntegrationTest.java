package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.repository.ChatRoomGroupAllocatorRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"chat.nickname-state.enabled=true", "spring.datasource.hikari.maximum-pool-size=8",
        "spring.datasource.hikari.data-source-properties.socketTimeout=20000"})
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:(?!0*3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupProvisionalStateRaceIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();

    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private ChatRoomGroupAllocatorRepository allocators;
    @Autowired private ChatRoomRepository rooms;
    @Autowired private ChatUserNicknameStateRepository states;
    @Autowired private ChatParticipantNicknameService nicknameParticipants;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChatRoomParticipantRepository participants;

    /** 기존 배정의 첫 조회가 대기하는 동안 이벤트는 임시 상태 PK를 기다리고 정리 후 반영된다. */
    @Test
    void initialExistingAssignmentCleansProvisionalStateBeforeSameVersionEvent() throws Exception {
        assertThat(properties.enabled()).isTrue();
        var initial = groups.joinOrCreate(request("봄바람", null));
        assertThat(initial.created()).isTrue();
        assertThat(stateCount()).isZero();
        long roomId = initial.response().chatRoomId();
        logEnvironment("V8a/V9");

        var executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            long blockerId = connectionId(blocker);
            try (var statement = blocker.prepareStatement("SELECT chat_room_participant_id FROM chat_room_participants WHERE group_date=? AND user_id=? FOR UPDATE")) {
                statement.setObject(1, date);
                statement.setLong(2, userId);
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            try {
                var group = executor.submit(() -> groups.joinOrCreate(request("여름밤", 8L)));
                var groupWait = awaitWait("chat_room_participants", blockerId, null);
                long groupConnection = number(groupWait, "requester");
                var event = executor.submit(() -> events.applyVersionedNickname(
                        new ChatNicknameVersionCommand(userId, "가을밤", 8L, UUID.randomUUID())));
                awaitWait("chat_user_nickname_states", groupConnection, "PRIMARY");
                assertThat(group.isDone()).isFalse();
                assertThat(event.isDone()).isFalse();
                blocker.commit();
                var result = outcome("V8a group", group);
                assertThat(result.created()).isFalse();
                assertThat(result.response().chatRoomId()).isEqualTo(roomId);
                assertThat(result.response().admittedParticipantCount()).isEqualTo(1);
                assertThat(outcome("V9 event", event).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
            } finally {
                blocker.rollback();
            }
        } finally {
            stopExecutor(executor);
        }
        assertFinal(roomId);
    }

    /** OFF 배정자가 실제 allocator를 보유한 동안 ON과 이벤트가 차례로 대기한 뒤 기존 배정을 보존한다. */
    @Test
    void lateExistingAssignmentCleansProvisionalStateBeforeSameVersionEvent() throws Exception {
        assertThat(properties.enabled()).isTrue();
        assertThat(stateCount()).isZero();
        logEnvironment("V8b/V9");
        var reachedSave = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var writerConnection = new AtomicLong();
        var participantProxy = new ProxyFactory(participants);
        participantProxy.addAdvice((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("saveAndFlush")) {
                writerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                reachedSave.countDown();
                if (!releaseWriter.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("OFF writer release timeout");
            }
            return invocation.proceed();
        });
        var offWriter = offGroupService((ChatRoomParticipantRepository) participantProxy.getProxy());

        var executor = Executors.newFixedThreadPool(3, Thread.ofPlatform().daemon().factory());
        try {
            var writer = executor.submit(() -> offWriter.joinOrCreate(request("봄바람", 8L)));
            try {
                assertThat(reachedSave.await(10, TimeUnit.SECONDS)).isTrue();
                assertAllocatorHeld(writerConnection.get());
                var group = executor.submit(() -> groups.joinOrCreate(request("여름밤", 8L)));
                var groupWait = awaitWait("chat_room_group_allocators", writerConnection.get(), null);
                long groupConnection = number(groupWait, "requester");
                var event = executor.submit(() -> events.applyVersionedNickname(
                        new ChatNicknameVersionCommand(userId, "가을밤", 8L, UUID.randomUUID())));
                awaitWait("chat_user_nickname_states", groupConnection, "PRIMARY");
                assertThat(group.isDone()).isFalse();
                assertThat(event.isDone()).isFalse();
                releaseWriter.countDown();
                var first = outcome("V8b OFF writer", writer);
                var second = outcome("V8b ON reader", group);
                assertThat(first.created()).isTrue();
                assertThat(second.created()).isFalse();
                assertThat(second.response().chatRoomId()).isEqualTo(first.response().chatRoomId());
                assertThat(second.response().admittedParticipantCount()).isEqualTo(1);
                assertThat(outcome("V9 event", event).status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
                assertFinal(first.response().chatRoomId());
            } finally {
                releaseWriter.countDown();
            }
        } finally {
            stopExecutor(executor);
        }
    }

    /** 고정 OFF resolver와 실제 트랜잭션 프록시를 별도로 구성해 운영 전환 중 writer를 재현한다. */
    private GroupChatRoomJoinService offGroupService(ChatRoomParticipantRepository writerParticipants) {
        var resolver = transactionalProxy(new ChatNicknameStateResolver(
                new ChatNicknameStateProperties(false), states, nicknameParticipants, clock));
        var transactionService = transactionalProxy(new GroupChatRoomJoinTransactionService(
                allocators, rooms, writerParticipants, resolver, clock));
        return new GroupChatRoomJoinService(transactionService, new GroupChatRoomJoinRetryService(transactionService));
    }

    /** 실제 Spring 트랜잭션 advice를 적용한 독립 서비스 프록시를 만든다. */
    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** 지정된 blocker와 테이블 사이의 실제 InnoDB 대기 관계를 제한 시간 안에 찾는다. */
    private Map<String, Object> awaitWait(String table, long blocker, String index) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<Map<String, Object>> waits = List.of();
        while (System.nanoTime() < deadline) {
            waits = jdbc.queryForList("""
                    SELECT rt.PROCESSLIST_ID AS requester, bt.PROCESSLIST_ID AS blocker,
                           r.OBJECT_NAME AS table_name, r.INDEX_NAME AS index_name,
                           r.LOCK_DATA AS lock_data, r.LOCK_MODE AS requested_mode,
                           b.LOCK_MODE AS blocking_mode, rt.PROCESSLIST_INFO AS requested_sql
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks r ON w.REQUESTING_ENGINE_LOCK_ID=r.ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks b ON w.BLOCKING_ENGINE_LOCK_ID=b.ENGINE_LOCK_ID
                    JOIN performance_schema.threads rt ON r.THREAD_ID=rt.THREAD_ID
                    JOIN performance_schema.threads bt ON b.THREAD_ID=bt.THREAD_ID
                    WHERE r.OBJECT_SCHEMA=DATABASE() AND r.OBJECT_NAME=? AND bt.PROCESSLIST_ID=?
                    """, table, blocker);
            for (var wait : waits) {
                if (index == null || index.equals(wait.get("index_name"))) {
                    if ("chat_user_nickname_states".equals(table)) {
                        assertThat(String.valueOf(wait.get("lock_data"))).isEqualTo(Long.toString(userId));
                    }
                    System.out.println("실제 잠금 대기=" + wait);
                    return wait;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("실제 잠금 대기 미관측: table=" + table + ", blocker=" + blocker + ", waits=" + waits);
    }

    /** OFF writer가 해당 날짜와 질문의 allocator 레코드에 실제 쓰기 잠금을 보유하는지 확인한다. */
    private void assertAllocatorHeld(long connectionId) {
        var locks = jdbc.queryForList("""
                SELECT l.INDEX_NAME AS index_name, l.LOCK_MODE AS lock_mode, l.LOCK_DATA AS lock_data
                FROM performance_schema.data_locks l JOIN performance_schema.threads t ON l.THREAD_ID=t.THREAD_ID
                WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_room_group_allocators'
                  AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED' AND t.PROCESSLIST_ID=?
                """, connectionId);
        System.out.println("OFF writer allocator 잠금=" + locks + ", connection=" + connectionId);
        assertThat(locks).anyMatch(row -> String.valueOf(row.get("lock_mode")).startsWith("X"));
    }

    /** 원래 호출을 재시도 없이 수집하고 SQL 오류 번호를 남긴다. */
    private <T> T outcome(String label, Future<T> future) throws Exception {
        try {
            T result = future.get(20, TimeUnit.SECONDS);
            System.out.println(label + " 결과=" + result);
            return result;
        } catch (Exception error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    System.out.println(label + " SQL 오류=" + sql.getErrorCode() + ", state=" + sql.getSQLState());
                }
            }
            throw error;
        }
    }

    /** 작업 중단을 요청하고 스레드 종료를 제한 시간 안에 확인한다. */
    private void stopExecutor(java.util.concurrent.ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
    }

    /** 커밋 후 상태, 기존 참가자, 정원과 allocator 순번을 새 연결에서 확인한다. */
    private void assertFinal(long roomId) {
        var state = jdbc.queryForMap("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId);
        var participant = jdbc.queryForMap("SELECT chat_room_id,nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", date, userId);
        System.out.println("최종 상태=" + state + ", 참가자=" + participant);
        assertThat(state.get("nickname")).isEqualTo("가을밤");
        assertThat(number(state, "nickname_version")).isEqualTo(8);
        assertThat(number(participant, "chat_room_id")).isEqualTo(roomId);
        assertThat(participant.get("nickname")).isEqualTo("가을밤");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants WHERE group_date=? AND user_id=?", Integer.class, date, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT admitted_participant_count FROM chat_rooms WHERE chat_room_id=?", Integer.class, roomId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_room_sequence FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(1);
    }

    /** 그룹 테스트 입력은 같은 날짜와 질문의 배정으로 고정한다. */
    private GroupChatJoinOrCreateRequest request(String nickname, Long version) {
        return new GroupChatJoinOrCreateRequest(date, questionId, userId, nickname, "A", version);
    }

    /** 사용자 상태가 미리 존재하지 않는지 확인한다. */
    private int stateCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", Integer.class, userId);
    }

    /** 독립 JDBC 연결의 식별자를 확인한다. */
    private long connectionId(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** JDBC 결과의 숫자를 정수형 크기에 관계없이 읽는다. */
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    /** 실제 서버 버전과 격리 수준, 설정과 테스트 입력을 기록한다. */
    private void logEnvironment(String scenario) {
        System.out.println(scenario + " enabled=" + properties.enabled() + ", requestVersion=8, eventVersion=8, poolMax=8, db="
                + jdbc.queryForMap("SELECT VERSION() AS version, @@transaction_isolation AS isolation_level"));
    }

    /** 이 테스트의 그룹 데이터와 상태만 삭제한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE p FROM chat_room_participants p JOIN chat_rooms r ON p.chat_room_id=r.chat_room_id WHERE r.group_date=? AND r.vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_rooms WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    /** 명시된 127.0.0.1 비3306 전용 그룹 DB의 연결 설정을 사용한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
