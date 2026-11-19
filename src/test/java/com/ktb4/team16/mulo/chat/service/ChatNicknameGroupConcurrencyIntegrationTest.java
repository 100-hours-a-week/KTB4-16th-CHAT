package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"chat.nickname-state.enabled=true", "spring.datasource.hikari.maximum-pool-size=40"})
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(?!0*3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupConcurrencyIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long baseId = IDS.addAndGet(1000);
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();
    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private ChatNicknameVersionService nicknameService;
    @MockitoSpyBean private JdbcTemplate jdbc;
    @MockitoSpyBean private ChatParticipantNicknameService nicknameParticipants;

    /** 기능 ON에서 상태가 없거나 기존 상태인 사용자들이 정원 경계를 지키는지 확인한다. */
    @ParameterizedTest
    @CsvSource({"30,0", "31,0", "61,0", "30,1", "31,1", "61,1", "30,2", "31,2", "61,2"})
    void enabledVersionedCapacityAcrossStateCohorts(int count, int cohort) throws Exception {
        assertThat(properties.enabled()).isTrue();
        for (int i = 0; i < count; i++) {
            if (cohort == 1 || (cohort == 2 && i % 2 == 0)) {
                jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '여름밤', 8, NOW())", baseId + i);
            }
        }
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        List<String> failures = new ArrayList<>();
        List<GroupChatRoomJoinService.Result> results = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(count);
        try {
            var futures = java.util.stream.IntStream.range(0, count).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("시작 신호 시간 초과");
                long version = (cohort == 1 || (cohort == 2 && i % 2 == 0)) ? 7L : 8L;
                return groups.joinOrCreate(new GroupChatJoinOrCreateRequest(date, questionId, baseId + i, "봄바람", "A", version));
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (int i = 0; i < futures.size(); i++) {
                try { results.add(futures.get(i).get(60, TimeUnit.SECONDS)); }
                catch (ExecutionException exception) { failures.add(i + ":" + failure(exception)); }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        System.out.println("ON cohort=" + cohort + ", 요청=" + count + ", 성공=" + results.size() + ", 실패=" + failures);
        assertThat(failures).isEmpty();
        assertThat(results).hasSize(count);
        assertThat(results.stream().filter(r -> r.response().roomSequence() == 1)).hasSize(Math.min(30, count));
        assertThat(results.stream().filter(r -> r.response().roomSequence() == 2)).hasSize(Math.max(0, Math.min(30, count - 30)));
        assertThat(results.stream().filter(r -> r.response().roomSequence() == 3)).hasSize(Math.max(0, count - 60));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants p JOIN chat_user_nickname_states s ON p.user_id=s.user_id WHERE p.group_date=? AND p.user_id BETWEEN ? AND ? AND p.nickname=s.nickname AND s.nickname_version=8", Integer.class, date, baseId, baseId + count - 1)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT SUM(admitted_participant_count) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(count);
        var roomCounts = jdbc.queryForList("SELECT admitted_participant_count FROM chat_rooms WHERE group_date=? AND vote_question_id=? ORDER BY room_sequence", Integer.class, date, questionId);
        assertThat(roomCounts).containsExactlyElementsOf(count <= 30 ? List.of(count) : count <= 60 ? List.of(30, count - 30) : List.of(30, 30, count - 60));
        assertThat(jdbc.queryForObject("SELECT last_room_sequence FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo((count + 29) / 30);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM chat_room_participants WHERE group_date=? AND user_id BETWEEN ? AND ?", Integer.class, date, baseId, baseId + count - 1)).isEqualTo(count);
    }

    /** 29명 배정 뒤 두 사용자 경합에서도 마지막 한 자리만 기존 방에 할당한다. */
    @ParameterizedTest
    @CsvSource({"0", "1", "2"})
    void enabledTwoRequestsCompeteForLastSeat(int cohort) throws Exception {
        assertThat(properties.enabled()).isTrue();
        for (int i = 0; i < 31; i++) {
            boolean existing = cohort == 1 || (cohort == 2 && i % 2 == 0);
            if (existing) jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '여름밤', 8, NOW())", baseId + i);
        }
        for (int i = 0; i < 29; i++) {
            boolean existing = cohort == 1 || (cohort == 2 && i % 2 == 0);
            groups.joinOrCreate(new GroupChatJoinOrCreateRequest(date, questionId, baseId + i, "봄바람", "A", existing ? 7L : 8L));
        }
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            var futures = java.util.stream.IntStream.range(29, 31).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("시작 신호 시간 초과");
                boolean existing = cohort == 1 || (cohort == 2 && i % 2 == 0);
                return groups.joinOrCreate(new GroupChatJoinOrCreateRequest(date, questionId, baseId + i, "봄바람", "A", existing ? 7L : 8L));
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                try { outcomes.add("성공 " + future.get(60, TimeUnit.SECONDS).response().roomSequence()); }
                catch (ExecutionException e) { outcomes.add(failure(e)); }
            }
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        System.out.println("ON cohort=" + cohort + " 29+2 원래 요청 결과=" + outcomes);
        assertThat(outcomes).containsExactlyInAnyOrder("성공 1", "성공 2");
        assertThat(jdbc.queryForList("SELECT admitted_participant_count FROM chat_rooms WHERE group_date=? AND vote_question_id=? ORDER BY room_sequence", Integer.class, date, questionId)).containsExactly(30, 1);
        assertThat(jdbc.queryForObject("SELECT last_room_sequence FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants p JOIN chat_user_nickname_states s ON p.user_id=s.user_id WHERE p.group_date=? AND p.user_id BETWEEN ? AND ? AND p.nickname=s.nickname AND s.nickname_version=8", Integer.class, date, baseId, baseId + 30)).isEqualTo(31);
    }

    /** 최초 상태 INSERT를 보유한 요청을 다른 입장이 실제 PK 잠금에서 기다리는지 확인한다. */
    @Test
    @SuppressWarnings("unchecked")
    void firstStateInsertSerializesSameUserRequests() throws Exception {
        assertThat(properties.enabled()).isTrue();
        var firstInserted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            int order = calls.incrementAndGet();
            Object result = invocation.callRealMethod();
            if (order == 1) {
                firstInserted.countDown();
                if (!releaseFirst.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("상태 잠금 해제 시간 초과");
            }
            return result;
        }).when(jdbc).execute(any(ConnectionCallback.class));
        var executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            var first = executor.submit(() -> groups.joinOrCreate(request(baseId, questionId)));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> groups.joinOrCreate(request(baseId, questionId)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            int waits = 0;
            while (System.nanoTime() < deadline) {
                waits = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='chat_user_nickname_states'
                        """, Integer.class);
                if (waits > 0) break;
                Thread.sleep(50);
            }
            System.out.println("ON 최초 상태 PK 잠금 대기 수=" + waits);
            assertThat(waits).isPositive();
            releaseFirst.countDown();
            for (var future : List.of(first, second)) {
                try { outcomes.add("성공 room=" + future.get(30, TimeUnit.SECONDS).response().chatRoomId()); }
                catch (ExecutionException e) { outcomes.add(failure(e)); }
            }
        } finally {
            releaseFirst.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(jdbc).execute(any(ConnectionCallback.class));
        }
        System.out.println("ON 최초 상태 경합 원래 요청 결과=" + outcomes);
        assertThat(outcomes).hasSize(2).allMatch(value -> value.startsWith("성공"));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants WHERE group_date=? AND user_id=?", Integer.class, date, baseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(admitted_participant_count) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nickname_version FROM chat_user_nickname_states WHERE user_id=?", Long.class, baseId)).isEqualTo(8L);
    }

    /** 기존 상태의 중복 INSERT 후 두 입장 요청을 동시에 잠그며 원래 데드락 결과를 기록한다. */
    @Test
    @SuppressWarnings("unchecked")
    void recordsOverlappingExistingStateInsertDeadlockWithoutRetry() throws Exception {
        assertThat(properties.enabled()).isTrue();
        jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '여름밤', 8, NOW())", baseId);
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(jdbc).execute(any(ConnectionCallback.class));
        List<String> outcomes = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> groups.joinOrCreate(request(baseId, questionId)));
            var second = executor.submit(() -> groups.joinOrCreate(request(baseId, questionId)));
            for (var future : List.of(first, second)) {
                try { var r = future.get(30, TimeUnit.SECONDS); outcomes.add("성공 room=" + r.response().chatRoomId()); }
                catch (ExecutionException e) { outcomes.add(failure(e)); }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(jdbc).execute(any(ConnectionCallback.class));
        }
        System.out.println("ON 동일 사용자 원래 요청 결과=" + outcomes);
        System.out.println("최종 상태=" + jdbc.queryForList("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", baseId));
        System.out.println("최종 배정=" + jdbc.queryForList("SELECT chat_room_id,nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", date, baseId));
        assertThat(outcomes).allMatch(value -> value.startsWith("성공"));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", String.class, date, baseId)).isEqualTo("여름밤");
        assertThat(jdbc.queryForObject("SELECT nickname_version FROM chat_user_nickname_states WHERE user_id=?", Long.class, baseId)).isEqualTo(8L);
    }

    /** 그룹 입장과 Chat-1 변경이 기존 상태를 동시에 잠글 때 원래 결과를 기록한다. */
    @Test
    @SuppressWarnings("unchecked")
    void recordsGroupAndNicknameEventOverlapWithoutRetry() throws Exception {
        assertThat(properties.enabled()).isTrue();
        jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '여름밤', 8, NOW())", baseId);
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            System.out.println("실제 연결: " + jdbc.queryForMap("SELECT CONNECTION_ID() AS connection_id, @@transaction_isolation AS isolation_level"));
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(jdbc).execute(any(ConnectionCallback.class));
        List<String> outcomes = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> groups.joinOrCreate(request(baseId, questionId)));
            var second = executor.submit(() -> nicknameService.applyVersionedNickname(
                    new ChatNicknameVersionCommand(baseId, "가을밤", 9, java.util.UUID.randomUUID())));
            for (var future : List.of(first, second)) {
                try { outcomes.add("성공 " + future.get(30, TimeUnit.SECONDS)); }
                catch (ExecutionException e) { outcomes.add(failure(e)); }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            doCallRealMethod().when(jdbc).execute(any(ConnectionCallback.class));
        }
        System.out.println("ON 입장과 이벤트 원래 요청 결과=" + outcomes);
        System.out.println("최종 상태=" + jdbc.queryForList("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", baseId));
        System.out.println("최종 배정=" + jdbc.queryForList("SELECT chat_room_id,nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", date, baseId));
        assertThat(outcomes).allMatch(value -> value.startsWith("성공"));
        assertThat(jdbc.queryForObject("SELECT nickname_version FROM chat_user_nickname_states WHERE user_id=?", Long.class, baseId)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", String.class, date, baseId)).isEqualTo("가을밤");
    }

    /** 30명 입장 중 한 사용자의 닉네임 이벤트가 실제 상태 잠금을 기다리고 이후 반영된다. */
    @Test
    @SuppressWarnings("unchecked")
    void eventContendsWithOneOfThirtyVersionedJoins() throws Exception {
        assertThat(properties.enabled()).isTrue();
        var stateLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var targetThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var connectionId = new AtomicLong();
        doAnswer(invocation -> {
            if (Thread.currentThread() == targetThread.get()) {
                connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                stateLocked.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("입장 잠금 해제 시간 초과");
            }
            return org.mockito.Mockito.mockingDetails(nicknameParticipants).getMockCreationSettings()
                    .getDefaultAnswer().answer(invocation);
        }).when(nicknameParticipants).updateNickname(any(), any());
        var executor = Executors.newFixedThreadPool(31);
        List<String> failures = new ArrayList<>();
        try {
            var target = executor.submit(() -> {
                targetThread.set(Thread.currentThread());
                return groups.joinOrCreate(request(baseId, questionId));
            });
            assertThat(stateLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var event = executor.submit(() -> nicknameService.applyVersionedNickname(
                    new ChatNicknameVersionCommand(baseId, "가을밤", 9, java.util.UUID.randomUUID())));
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
                Thread.sleep(50);
            }
            System.out.println("30명 중 상태 PK 대기=" + waits + ", 보유 연결=" + connectionId.get());
            if (waits == 0 && event.isDone()) {
                try { System.out.println("이벤트 조기 종료=" + event.get()); }
                catch (ExecutionException e) { System.out.println("이벤트 조기 실패=" + failure(e)); }
            }
            assertThat(waits).isPositive();
            var othersStarted = new CountDownLatch(29);
            var others = java.util.stream.IntStream.range(1, 30)
                    .mapToObj(i -> executor.submit(() -> {
                        othersStarted.countDown();
                        return groups.joinOrCreate(request(baseId + i, questionId));
                    }))
                    .toList();
            assertThat(othersStarted.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            try { target.get(60, TimeUnit.SECONDS); } catch (ExecutionException e) { failures.add("target=" + failure(e)); }
            for (int i = 0; i < others.size(); i++) {
                try { others.get(i).get(60, TimeUnit.SECONDS); }
                catch (ExecutionException e) { failures.add("join" + i + "=" + failure(e)); }
            }
            try { System.out.println("30명 경합 이벤트=" + event.get(60, TimeUnit.SECONDS)); }
            catch (ExecutionException e) { failures.add("event=" + failure(e)); }
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            org.mockito.Mockito.reset(nicknameParticipants);
        }
        System.out.println("30명 경합 원래 요청 실패=" + failures);
        System.out.println("경합 사용자 상태=" + jdbc.queryForList("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", baseId));
        System.out.println("경합 사용자 배정=" + jdbc.queryForList("SELECT chat_room_id,nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", date, baseId));
        assertThat(failures).isEmpty();
        assertThat(jdbc.queryForObject("SELECT nickname_version FROM chat_user_nickname_states WHERE user_id=?", Long.class, baseId)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", String.class, date, baseId)).isEqualTo("가을밤");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants WHERE group_date=? AND user_id BETWEEN ? AND ?", Integer.class, date, baseId, baseId + 29)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT SUM(admitted_participant_count) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isEqualTo(30);
    }

    /** SQL 원인 체인에서 DB 오류 번호를 보존한다. */
    private String failure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) return "SQL=" + sql.getErrorCode() + " state=" + sql.getSQLState() + " " + sql.getMessage();
        }
        return error.getClass().getSimpleName() + ":" + error.getMessage();
    }

    /** 모든 요청은 확정된 버전 8을 전달한다. */
    private GroupChatJoinOrCreateRequest request(long userId, long question) {
        return new GroupChatJoinOrCreateRequest(date, question, userId, "봄바람", "A", 8L);
    }

    /** 이 테스트만의 상태와 그룹 데이터를 정리한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE p FROM chat_room_participants p JOIN chat_rooms r ON p.chat_room_id=r.chat_room_id WHERE r.group_date=? AND r.vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_rooms WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id BETWEEN ? AND ?", baseId, baseId + 100);
    }

    /** 그룹 테스트는 전용 DB로 연결한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
