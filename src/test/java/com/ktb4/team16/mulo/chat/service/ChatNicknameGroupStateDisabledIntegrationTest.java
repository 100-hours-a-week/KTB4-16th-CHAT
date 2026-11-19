package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.repository.ChatUserNicknameStateRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

// 기본 설정에서 상태 기능이 꺼진 경우의 네 가지 입력 조합을 실행한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches = DisabledGroupStateScenarios.LOCAL_DATABASE)
class ChatNicknameGroupStateDisabledIntegrationTest extends DisabledGroupStateScenarios {
}

// 명시적으로 false인 설정에서도 같은 네 가지 입력 조합을 실행한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"chat.websocket.allowed-origins=http://localhost", "chat.nickname-state.enabled=false"})
@EnabledIfEnvironmentVariable(named = "CHAT_GROUP_TEST_DB_URL", matches = DisabledGroupStateScenarios.LOCAL_DATABASE)
class ChatNicknameGroupStateExplicitFalseIntegrationTest extends DisabledGroupStateScenarios {
}

abstract class DisabledGroupStateScenarios {
    // 전용 데이터베이스와 명시적 루프백 포트만 허용하고 기본 MySQL 포트는 제외한다.
    static final String LOCAL_DATABASE = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):(?!0*3306/)\\d{2,5}/mulo_chat_group_test(?:\\?.*)?";
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();

    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ChatUserNicknameStateRepository states;
    @MockitoSpyBean private ChatRoomEventDeliveryListener listener;

    // 상태가 없고 버전도 없으면 요청 이름으로만 새 참가자를 만든다.
    @Test
    void noStateAndNoVersionLeaveExistingDataUntouched() {
        assertDisabledScenario(false, null);
    }

    // 상태가 없어도 요청 버전만으로 첫 상태를 만들지 않는다.
    @Test
    void noStateAndVersionLeaveExistingDataUntouched() {
        assertDisabledScenario(false, 8L);
    }

    // 상태가 있어도 버전 없는 요청 이름을 사용하고 상태를 유지한다.
    @Test
    void existingStateAndNoVersionLeaveExistingDataUntouched() {
        assertDisabledScenario(true, null);
    }

    // 상태와 버전이 모두 있어도 요청 이름을 사용하고 상태를 유지한다.
    @Test
    void existingStateAndVersionLeaveExistingDataUntouched() {
        assertDisabledScenario(true, 8L);
    }

    // 활성 기존 참가자와 상태의 DB 스냅샷 및 커밋 후 전달 여부를 비교한다.
    private void assertDisabledScenario(boolean existingState, Long version) {
        assertThat(properties.enabled()).isFalse();
        long oldRoomId = createActiveExistingParticipant();
        if (existingState) {
            jdbc.update("INSERT INTO chat_user_nickname_states (user_id, nickname, nickname_version, updated_at) VALUES (?, '상태이름', 5, NOW(6))", userId);
        }
        List<Map<String, Object>> previousState = stateSnapshot();
        List<Map<String, Object>> previousParticipant = participantSnapshot(oldRoomId);
        reset(states, listener);

        var result = groups.joinOrCreate(new GroupChatJoinOrCreateRequest(date, questionId, userId, "요청이름", "A", version));

        assertThat(result.created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT nickname FROM chat_room_participants WHERE chat_room_id=? AND user_id=?",
                String.class, result.response().chatRoomId(), userId)).isEqualTo("요청이름");
        assertThat(stateSnapshot()).isEqualTo(previousState);
        assertThat(participantSnapshot(oldRoomId)).isEqualTo(previousParticipant);
        verifyNoInteractions(states);
        verify(listener, never()).deliverParticipantNicknameChanged(any());
    }

    // 일일 사용자 유일 제약을 지키면서 알림 대상이 되는 과거 날짜의 활성 그룹 참가자를 만든다.
    private long createActiveExistingParticipant() {
        LocalDate previousDate = date.minusDays(1);
        jdbc.update("""
                INSERT INTO chat_rooms (match_id, expires_at, room_type, group_date, vote_question_id,
                    room_sequence, capacity, admitted_participant_count)
                VALUES (NULL, DATE_ADD(NOW(), INTERVAL 1 DAY), 'GROUP', ?, ?, 1, 30, 1)
                """, previousDate, questionId);
        long roomId = jdbc.queryForObject("SELECT chat_room_id FROM chat_rooms WHERE group_date=? AND vote_question_id=?",
                Long.class, previousDate, questionId);
        jdbc.update("""
                INSERT INTO chat_room_participants (chat_room_id, user_id, nickname, joined_at, group_date, vote_choice)
                VALUES (?, ?, '기존이름', NOW(), ?, 'A')
                """, roomId, userId, previousDate);
        return roomId;
    }

    // 사용자 상태의 값과 수정 시각까지 읽어 변경 여부를 확인한다.
    private List<Map<String, Object>> stateSnapshot() {
        return jdbc.queryForList("SELECT nickname, nickname_version, updated_at FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    // 기존 참가자의 모든 영속 필드를 읽어 변경 여부를 확인한다.
    private List<Map<String, Object>> participantSnapshot(long roomId) {
        return jdbc.queryForList("SELECT * FROM chat_room_participants WHERE chat_room_id=? AND user_id=?", roomId, userId);
    }

    // 이번 시나리오의 사용자와 질문에 속한 행만 정리한다.
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM chat_room_participants WHERE user_id=? AND chat_room_id IN (SELECT chat_room_id FROM chat_rooms WHERE vote_question_id=? AND group_date IN (?, ?))", userId, questionId, date, date.minusDays(1));
        jdbc.update("""
                DELETE FROM chat_rooms WHERE vote_question_id=? AND group_date IN (?, ?)
                AND NOT EXISTS (SELECT 1 FROM chat_room_participants p WHERE p.chat_room_id=chat_rooms.chat_room_id)
                """, questionId, date, date.minusDays(1));
        jdbc.update("""
                DELETE FROM chat_room_group_allocators WHERE vote_question_id=? AND group_date=?
                AND NOT EXISTS (SELECT 1 FROM chat_rooms r WHERE r.vote_question_id=? AND r.group_date=?)
                """, questionId, date, questionId, date);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    // 전용 그룹 테스트 DB 자격 증명을 Spring 데이터 소스에 연결한다.
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
