package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "chat.nickname-state.enabled=true",
        "chat.websocket.allowed-origins=http://localhost"
})
@EnabledIfEnvironmentVariable(
        named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):(?!0*3306/)\\d{2,5}/mulo_chat_group_test(?:\\?.*)?"
)
class ChatNicknameGroupPolicyMatrixIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate today = ChatTime.now().toLocalDate();

    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private JdbcTemplate jdbc;

    // 상태 8보다 오래된 입장 버전 7은 저장된 이름과 버전을 유지한다.
    @Test
    void existingStateWinsOverOlderRequest() {
        assertExistingStateWins(7L);
    }

    // 상태 8과 같은 버전의 다른 입장 이름도 충돌 없이 저장된 이름을 사용한다.
    @Test
    void existingStateWinsOverSameVersionDifferentName() {
        assertExistingStateWins(8L);
    }

    // 상태 8보다 높은 입장 버전 9도 상태를 전진시키거나 이벤트를 선반영하지 않는다.
    @Test
    void existingStateWinsOverNewerRequestUntilEvent() {
        assertExistingStateWins(9L);
    }

    // 무버전 입장도 상태 8이 존재하면 저장된 이름을 사용한다.
    @Test
    void existingStateWinsOverUnversionedRequest() {
        assertExistingStateWins(null);
    }

    // 무상태·무버전 입장은 요청 이름을 사용하고 상태와 기존 참가자를 변경하지 않는다.
    @Test
    void unversionedRequestWithoutStateDoesNotCreateOrReconcileState() {
        assertFirstStatePolicy(null);
    }

    // 무상태·버전 0 입장은 첫 상태를 만들고 기존 참가자 이름을 함께 보정한다.
    @Test
    void versionZeroCreatesFirstStateAndReconcilesExistingParticipant() {
        assertFirstStatePolicy(0L);
    }

    // 무상태·버전 8 입장도 첫 상태와 기존 참가자 이름을 함께 맞춘다.
    @Test
    void versionEightCreatesFirstStateAndReconcilesExistingParticipant() {
        assertFirstStatePolicy(8L);
    }

    // 저장 상태의 이름·버전·수정 시각을 입장 전후 비교하고 실제 후속 이벤트를 검증한다.
    private void assertExistingStateWins(Long requestVersion) {
        assertThat(properties.enabled()).isTrue();
        long previousRoom = createPreviousGroupParticipant();
        jdbc.update("""
                INSERT INTO chat_user_nickname_states (user_id, nickname, nickname_version, updated_at)
                VALUES (?, '여름밤', 8, NOW(6))
                """, userId);
        List<Map<String, Object>> stateBefore = stateSnapshot();

        var result = groups.joinOrCreate(request("다른이름", requestVersion));

        assertThat(result.created()).isTrue();
        assertThat(stateSnapshot()).isEqualTo(stateBefore);
        assertParticipant(previousRoom, "봄바람");
        assertParticipant(result.response().chatRoomId(), "여름밤");
        // 요청 버전 9가 실제 이벤트 9를 중복 처리한 것으로 오인되지 않아야 한다.
        assertAppliedEvent(previousRoom, result.response().chatRoomId(), 9L);
    }

    // 최초 버전 유무에 따른 상태 생성과 기존 참가자 보정의 정확한 차이를 검증한다.
    private void assertFirstStatePolicy(Long requestVersion) {
        assertThat(properties.enabled()).isTrue();
        long previousRoom = createPreviousGroupParticipant();
        assertThat(stateSnapshot()).isEmpty();

        var result = groups.joinOrCreate(request("요청이름", requestVersion));

        assertThat(result.created()).isTrue();
        assertParticipant(result.response().chatRoomId(), "요청이름");
        if (requestVersion == null) {
            assertThat(stateSnapshot()).isEmpty();
            assertParticipant(previousRoom, "봄바람");
        } else {
            assertState("요청이름", requestVersion);
            assertParticipant(previousRoom, "요청이름");
        }
        assertAppliedEvent(previousRoom, result.response().chatRoomId(), requestVersion == null ? 8L : requestVersion + 1);
    }

    // 실제 버전 이벤트 커밋 후 상태와 과거·현재 그룹 참가자가 모두 변경됐는지 확인한다.
    private void assertAppliedEvent(long previousRoom, long newRoom, long eventVersion) {
        var eventResult = events.applyVersionedNickname(
                new ChatNicknameVersionCommand(userId, "가을밤", eventVersion, UUID.randomUUID()));
        assertThat(eventResult.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
        assertThat(eventResult.updatedParticipantCount()).isEqualTo(2);
        assertState("가을밤", eventVersion);
        assertParticipant(previousRoom, "가을밤");
        assertParticipant(newRoom, "가을밤");
    }

    // 실제 그룹 만료 정책과 일일 사용자 유일 제약을 지키는 전날 참가자를 준비한다.
    private long createPreviousGroupParticipant() {
        LocalDate previousDate = today.minusDays(1);
        jdbc.update("""
                INSERT INTO chat_rooms (match_id, created_at, expires_at, room_type, group_date, vote_question_id,
                    room_sequence, capacity, admitted_participant_count)
                VALUES (NULL, ?, ?, 'GROUP', ?, ?, 1, 30, 1)
                """, previousDate.atStartOfDay(), today.atStartOfDay(), previousDate, questionId);
        jdbc.update("""
                INSERT INTO chat_room_group_allocators (group_date, vote_question_id, last_room_sequence)
                VALUES (?, ?, 1)
                """, previousDate, questionId);
        long roomId = jdbc.queryForObject(
                "SELECT chat_room_id FROM chat_rooms WHERE group_date=? AND vote_question_id=?",
                Long.class, previousDate, questionId);
        jdbc.update("""
                INSERT INTO chat_room_participants (chat_room_id, user_id, nickname, joined_at, group_date, vote_choice)
                VALUES (?, ?, '봄바람', ?, ?, 'A')
                """, roomId, userId, previousDate.atStartOfDay().plusHours(12), previousDate);
        return roomId;
    }

    // 현재 날짜와 시나리오 전용 식별자로 그룹 입장 요청을 만든다.
    private GroupChatJoinOrCreateRequest request(String nickname, Long version) {
        return new GroupChatJoinOrCreateRequest(today, questionId, userId, nickname, "A", version);
    }

    // 커밋된 사용자 상태를 별도 JDBC 조회로 읽는다.
    private List<Map<String, Object>> stateSnapshot() {
        return jdbc.queryForList(
                "SELECT nickname, nickname_version, updated_at FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    // 정확한 한 개의 상태 행과 이름·버전을 확인한다.
    private void assertState(String nickname, long version) {
        var rows = stateSnapshot();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("nickname")).isEqualTo(nickname);
        assertThat(((Number) rows.getFirst().get("nickname_version")).longValue()).isEqualTo(version);
    }

    // 지정 그룹의 이 사용자 참가자 이름을 커밋 후 조회한다.
    private void assertParticipant(long roomId, String nickname) {
        assertThat(jdbc.queryForObject(
                "SELECT nickname FROM chat_room_participants WHERE chat_room_id=? AND user_id=?",
                String.class, roomId, userId)).isEqualTo(nickname);
    }

    // 이번 사용자 행과 다른 참가자가 없는 시나리오 전용 그룹만 정리한다.
    @AfterEach
    void cleanup() {
        jdbc.update("""
                DELETE FROM chat_room_participants WHERE user_id=? AND chat_room_id IN
                (SELECT chat_room_id FROM chat_rooms WHERE vote_question_id=? AND group_date IN (?, ?))
                """, userId, questionId, today, today.minusDays(1));
        jdbc.update("""
                DELETE FROM chat_rooms WHERE vote_question_id=? AND group_date IN (?, ?)
                AND NOT EXISTS (SELECT 1 FROM chat_room_participants p WHERE p.chat_room_id=chat_rooms.chat_room_id)
                """, questionId, today, today.minusDays(1));
        jdbc.update("""
                DELETE FROM chat_room_group_allocators WHERE vote_question_id=? AND group_date IN (?, ?)
                AND NOT EXISTS (SELECT 1 FROM chat_rooms r
                    WHERE r.vote_question_id=chat_room_group_allocators.vote_question_id
                    AND r.group_date=chat_room_group_allocators.group_date)
                """, questionId, today, today.minusDays(1));
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    // 전용 그룹 테스트 DB의 연결 정보만 Spring 데이터 소스에 전달한다.
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
