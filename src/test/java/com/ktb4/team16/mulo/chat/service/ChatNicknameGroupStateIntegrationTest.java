package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "chat.nickname-state.enabled=true")
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:(?!0*3306/)[0-9]+/mulo_chat_group_test(?:\\?.*)?")
class ChatNicknameGroupStateIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();
    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ChatRoomParticipantRepository participants;

    /** 입장 요청의 높은 버전은 상태를 올리지 않으며 실제 변경 이벤트가 올린다. */
    @Test
    void existingStateWinsUntilVersionedEventCommits() {
        jdbc.update("INSERT INTO chat_user_nickname_states (user_id,nickname,nickname_version,updated_at) VALUES (?, '여름밤', 8, NOW())", userId);
        groups.joinOrCreate(request("가을밤", 9L));
        assertState("여름밤", 8);
        assertParticipant("여름밤");
        events.applyVersionedNickname(new ChatNicknameVersionCommand(userId, "가을밤", 9, UUID.randomUUID()));
        assertState("가을밤", 9);
        assertParticipant("가을밤");
    }

    /** 기존 배정 재요청이 임시로 만든 첫 상태는 정리하고 기존 이름과 정원은 유지한다. */
    @Test
    void existingAssignmentDiscardsOnlyProvisionalState() {
        var initial = groups.joinOrCreate(request("봄바람", null));
        assertThat(stateCount()).isZero();
        var duplicate = groups.joinOrCreate(request("여름밤", 8L));
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.response().chatRoomId()).isEqualTo(initial.response().chatRoomId());
        assertThat(duplicate.response().admittedParticipantCount()).isEqualTo(1);
        assertStateCount(0);
        assertParticipant("봄바람");
    }

    /** 참가자 저장 실패 시 새 상태, 방, 정원과 allocator가 함께 롤백된다. */
    @Test
    void participantFailureRollsBackFirstStateAndRoom() {
        doThrow(new DataIntegrityViolationException("test failure")).when(participants).saveAndFlush(any(ChatRoomParticipant.class));
        assertThatThrownBy(() -> groups.joinOrCreate(request("여름밤", 8L))).isInstanceOf(DataIntegrityViolationException.class);
        assertStateCount(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", Integer.class, date, questionId)).isZero();
    }

    /** 상태 테이블의 현재 이름과 버전을 확인한다. */
    private void assertState(String nickname, long version) {
        var row = jdbc.queryForMap("SELECT nickname,nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId);
        assertThat(row.get("nickname")).isEqualTo(nickname);
        assertThat(((Number) row.get("nickname_version")).longValue()).isEqualTo(version);
    }

    /** 이 사용자의 참가자 이름을 확인한다. */
    private void assertParticipant(String nickname) {
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE group_date=? AND user_id=?", String.class, date, userId)).isEqualTo(nickname);
    }

    /** 이 사용자의 상태 행 수를 센다. */
    private int stateCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", Integer.class, userId); }
    /** 상태 행이 기대한 수인지 확인한다. */
    private void assertStateCount(int count) { assertThat(stateCount()).isEqualTo(count); }
    /** 입장 요청에 선택적 버전을 포함한다. */
    private GroupChatJoinOrCreateRequest request(String nickname, Long version) { return new GroupChatJoinOrCreateRequest(date, questionId, userId, nickname, "A", version); }

    /** 테스트 사용자가 만든 행만 정리한다. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE p FROM chat_room_participants p JOIN chat_rooms r ON p.chat_room_id=r.chat_room_id WHERE r.group_date=? AND r.vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_rooms WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?", date, questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    /** 격리된 그룹 테스트 DB로 연결한다. */
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
