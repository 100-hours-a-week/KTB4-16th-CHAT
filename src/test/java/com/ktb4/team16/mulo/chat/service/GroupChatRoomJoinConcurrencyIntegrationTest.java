package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@EnabledIfEnvironmentVariable(
        named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://[^/]+/mulo_chat_group_test(?:\\?.*)?"
)
class GroupChatRoomJoinConcurrencyIntegrationTest {

    private static final AtomicLong QUESTION_SEQUENCE = new AtomicLong(System.currentTimeMillis());
    private final LocalDate groupDate = ChatTime.now().toLocalDate();
    private final long voteQuestionId = QUESTION_SEQUENCE.incrementAndGet();
    private final long directMatchId = voteQuestionId + 50_000_000L;

    @Autowired
    private GroupChatRoomJoinService joinService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ChatRoomRestService chatRoomRestService;

    @Autowired
    private ChatRoomCreateService chatRoomCreateService;

    @Autowired
    private com.ktb4.team16.mulo.chat.repository.ChatRoomRepository chatRoomRepository;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }

    @Test
    @DisplayName("첫 방의 30자리 동시 배정은 정확히 30명으로 제한하고 다음 사용자는 2번 방에 배정한다")
    void serializesFirstRoomCreationAndCapacityAcrossConcurrentRequests() throws Exception {
        List<GroupChatRoomJoinService.Result> results = runConcurrent(31, index -> request(8_000_000L + index));

        assertThat(results).hasSize(31);
        assertThat(results.stream().filter(result -> result.response().roomSequence() == 1)).hasSize(30);
        assertThat(results.stream().filter(result -> result.response().roomSequence() == 2)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT SUM(admitted_participant_count)
                FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, voteQuestionId)).isEqualTo(31);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, voteQuestionId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT MAX(admitted_participant_count) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, voteQuestionId)).isEqualTo(30);
    }

    @Test
    @DisplayName("61번째 누적 배정부터 세 번째 방을 사용한다")
    void assignsSixtyFirstUserToThirdRoom() throws Exception {
        List<GroupChatRoomJoinService.Result> results = runConcurrent(61, index -> request(8_100_000L + index));

        assertThat(results.stream().filter(result -> result.response().roomSequence() == 1)).hasSize(30);
        assertThat(results.stream().filter(result -> result.response().roomSequence() == 2)).hasSize(30);
        assertThat(results.stream().filter(result -> result.response().roomSequence() == 3)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT MAX(room_sequence) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, voteQuestionId)).isEqualTo(3);
    }

    @Test
    @DisplayName("동일 사용자의 동시 재요청은 같은 배정 한 건만 반환한다")
    void serializesConcurrentDuplicateAssignment() throws Exception {
        List<GroupChatRoomJoinService.Result> results = runConcurrent(8, ignored -> request(9_000_001L));

        assertThat(results).extracting(result -> result.response().chatRoomId())
                .containsOnly(results.getFirst().response().chatRoomId());
        assertThat(results.stream().filter(GroupChatRoomJoinService.Result::created)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chat_room_participants
                WHERE group_date = ? AND user_id = ?
                """, Integer.class, groupDate, 9_000_001L)).isEqualTo(1);
    }

    @Test
    @DisplayName("29자리 방에 두 요청이 동시에 도착하면 한 명만 마지막 자리를 받고 나머지는 다음 방에 배정한다")
    void serializesConcurrentLastSeats() throws Exception {
        for (long userId = 10_000_000L; userId < 10_000_029L; userId++) {
            joinService.joinOrCreate(request(userId));
        }

        List<GroupChatRoomJoinService.Result> results = runConcurrent(2, index -> request(10_000_029L + index));

        assertThat(results.stream().map(result -> result.response().roomSequence()).sorted().toList())
                .containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT MAX(admitted_participant_count) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, voteQuestionId)).isEqualTo(30);
    }

    @Test
    @DisplayName("배정 트랜잭션 롤백 시 누적 정원과 방 순번을 복구한다")
    void restoresCapacityAndSequenceAfterRollback() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            joinService.joinOrCreate(request(11_000_001L));
            status.setRollbackOnly();
        });

        assertThat(groupRoomCount(voteQuestionId)).isZero();
        assertThat(groupAdmissionCount(voteQuestionId)).isZero();

        GroupChatRoomJoinService.Result next = joinService.joinOrCreate(request(11_000_002L));
        assertThat(next.response().roomSequence()).isEqualTo(1);
        assertThat(next.response().admittedParticipantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("퇴장으로 활성 인원이 줄어도 누적 정원은 재사용하지 않는다")
    void doesNotReuseAdmissionAfterParticipantLeaves() {
        List<GroupChatRoomJoinService.Result> results = new ArrayList<>();
        for (long userId = 12_000_000L; userId < 12_000_030L; userId++) {
            results.add(joinService.joinOrCreate(request(userId)));
        }
        Long firstRoomId = results.getFirst().response().chatRoomId();
        Long firstUserId = 12_000_000L;
        chatRoomRestService.enter(firstRoomId, firstUserId);
        chatRoomRestService.leave(firstRoomId, firstUserId);

        GroupChatRoomJoinService.Result next = joinService.joinOrCreate(request(12_000_030L));

        assertThat(next.response().roomSequence()).isEqualTo(2);
        assertThat(next.response().admittedParticipantCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT admitted_participant_count FROM chat_rooms WHERE chat_room_id = ?
                """, Integer.class, firstRoomId)).isEqualTo(30);
    }

    @Test
    @DisplayName("질문 ID가 다르면 같은 날짜에도 그룹방을 분리한다")
    void separatesGroupsByVoteQuestionId() {
        GroupChatRoomJoinService.Result first = joinService.joinOrCreate(request(13_000_001L));
        GroupChatJoinOrCreateRequest otherQuestionRequest = new GroupChatJoinOrCreateRequest(
                groupDate, voteQuestionId + 1, 13_000_002L, "tester", "B");

        GroupChatRoomJoinService.Result second = joinService.joinOrCreate(otherQuestionRequest);

        assertThat(second.response().chatRoomId()).isNotEqualTo(first.response().chatRoomId());
        assertThat(second.response().roomSequence()).isEqualTo(1);
        assertThat(groupRoomCount(voteQuestionId + 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘이 아닌 날짜는 그룹방을 만들지 않는다")
    void doesNotCreateGroupForDifferentDate() {
        GroupChatJoinOrCreateRequest otherDateRequest = new GroupChatJoinOrCreateRequest(
                groupDate.plusDays(1), voteQuestionId, 13_000_003L, "tester", "A");

        assertThatThrownBy(() -> joinService.joinOrCreate(otherDateRequest))
                .isInstanceOf(com.ktb4.team16.mulo.chat.exception.ChatApiException.class)
                .extracting(exception -> ((com.ktb4.team16.mulo.chat.exception.ChatApiException) exception)
                        .getErrorCode())
                .isEqualTo(com.ktb4.team16.mulo.chat.exception.ChatErrorCode.INVALID_INPUT_VALUE);
        assertThat(groupRoomCount(voteQuestionId)).isZero();
    }

    @Test
    @DisplayName("V4 이후에도 기존 DIRECT 방 생성과 기본 정원을 유지한다")
    void keepsDirectRoomDataCompatibleAfterMigration() {
        OffsetDateTime expiresAt = groupDate.plusDays(1).atStartOfDay(ChatTime.KST).toOffsetDateTime();
        CreateChatRoomRequest request = new CreateChatRoomRequest(directMatchId, expiresAt, List.of(
                new CreateChatRoomRequest.Participant(14_000_001L, "directA"),
                new CreateChatRoomRequest.Participant(14_000_002L, "directB")));

        ChatRoomCreateService.Result result = chatRoomCreateService.create(request);
        ChatRoom room = chatRoomRepository.findByMatchId(directMatchId).orElseThrow();

        assertThat(result.response().chatRoomId()).isNotNull();
        assertThat(room.getRoomType()).isEqualTo(ChatRoom.DIRECT_ROOM_TYPE);
        assertThat(room.getCapacity()).isEqualTo(2);
        assertThat(room.getAdmittedParticipantCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chat_room_participants WHERE chat_room_id = ?
                """, Integer.class, room.getChatRoomId())).isEqualTo(2);
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() {
        jdbcTemplate.update("""
                DELETE participant FROM chat_room_participants participant
                JOIN chat_rooms room ON room.chat_room_id = participant.chat_room_id
                WHERE room.group_date = ? AND room.vote_question_id IN (?, ?)
                """, groupDate, voteQuestionId, voteQuestionId + 1);
        jdbcTemplate.update("DELETE FROM chat_rooms WHERE group_date = ? AND vote_question_id IN (?, ?)",
                groupDate, voteQuestionId, voteQuestionId + 1);
        jdbcTemplate.update("DELETE FROM chat_room_group_allocators WHERE group_date = ? AND vote_question_id IN (?, ?)",
                groupDate, voteQuestionId, voteQuestionId + 1);
        jdbcTemplate.update("""
                DELETE participant FROM chat_room_participants participant
                JOIN chat_rooms room ON room.chat_room_id = participant.chat_room_id
                WHERE room.match_id = ?
                """, directMatchId);
        jdbcTemplate.update("DELETE FROM chat_rooms WHERE match_id = ?", directMatchId);
    }

    private GroupChatJoinOrCreateRequest request(long userId) {
        return new GroupChatJoinOrCreateRequest(groupDate, voteQuestionId, userId, "tester", "A");
    }

    private int groupRoomCount(long questionId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, questionId);
    }

    private int groupAdmissionCount(long questionId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(admitted_participant_count), 0) FROM chat_rooms
                WHERE group_date = ? AND vote_question_id = ? AND room_type = 'GROUP'
                """, Integer.class, groupDate, questionId);
    }

    private List<GroupChatRoomJoinService.Result> runConcurrent(int count, RequestFactory requestFactory)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            var futures = java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("동시 입장 테스트 시작 신호를 받지 못했습니다.");
                        }
                        return joinService.joinOrCreate(requestFactory.create(index));
                    }))
                    .toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(45, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("동시 입장 요청이 완료되지 않았습니다.", exception);
                }
            }).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(20, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface RequestFactory {
        GroupChatJoinOrCreateRequest create(int index);
    }
}
