package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.config.ChatNicknameStateProperties;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "chat.nickname-state.enabled=true",
        "chat.websocket.allowed-origins=http://localhost"
})
@EnabledIfEnvironmentVariable(
        named = "CHAT_GROUP_TEST_DB_URL",
        matches = "jdbc:mysql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]):(?!0*3306/)\\d{2,5}/mulo_chat_group_test(?:\\?.*)?"
)
class ChatNicknameLegacyGroupRaceIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private final long userId = IDS.incrementAndGet();
    private final long questionId = IDS.incrementAndGet();
    private final LocalDate date = ChatTime.now().toLocalDate();

    @Autowired private ChatNicknameStateProperties properties;
    @Autowired private GroupChatRoomJoinService groups;
    @Autowired private ChatNicknameVersionService events;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ChatNicknameStateResolver resolver;

    // 빈 상태 결정 후 첫 이벤트가 먼저 커밋되면 무버전 그룹 참가자에 과거 이름이 남을 수 있다.
    @Test
    void firstVersionEventCanCommitAfterMockedEmptyResolutionBeforeUnversionedGroupInsert() throws Exception {
        assertThat(properties.enabled()).isTrue();
        assertThat(stateCount()).isZero();
        CountDownLatch emptyRead = new CountDownLatch(1);
        CountDownLatch resumeGroup = new CountDownLatch(1);

        // resolver의 무상태 결과를 모의해 순서만 제어한다. 실제 상태 잠금 조회나
        // 누락 행의 잠금 범위를 검증한 결과로 해석하지 않는다.
        when(resolver.resolveNickname(userId, "봄바람", null)).thenAnswer(invocation -> {
            assertThat(stateCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                    .isEqualToIgnoringCase("READ-COMMITTED");
            emptyRead.countDown();
            if (!resumeGroup.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("그룹 요청 재개 신호를 받지 못했습니다.");
            }
            return new ChatNicknameStateResolver.Resolution("봄바람", false);
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var group = executor.submit(() -> groups.joinOrCreate(
                    new GroupChatJoinOrCreateRequest(date, questionId, userId, "봄바람", "A", null)));
            assertThat(emptyRead.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stateCount()).isZero();
            assertThat(participantCount()).isZero();

            var event = executor.submit(() -> events.applyVersionedNickname(
                    new ChatNicknameVersionCommand(userId, "여름밤", 8L, UUID.randomUUID())));
            var eventResult = event.get(10, TimeUnit.SECONDS);
            assertThat(eventResult.status()).isEqualTo(ChatNicknameVersionResult.Status.APPLIED);
            assertThat(eventResult.updatedParticipantCount()).isZero();
            assertThat(group.isDone()).isFalse();
            assertState("여름밤", 8L);
            assertThat(participantCount()).isZero();

            resumeGroup.countDown();
            var groupResult = group.get(10, TimeUnit.SECONDS);
            assertThat(groupResult.created()).isTrue();
            assertState("여름밤", 8L);
            assertThat(jdbc.queryForObject("""
                    SELECT nickname FROM chat_room_participants WHERE chat_room_id=? AND user_id=?
                    """, String.class, groupResult.response().chatRoomId(), userId)).isEqualTo("봄바람");
            assertThat(participantCount()).isEqualTo(1);
        } finally {
            resumeGroup.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    // 시나리오 사용자의 상태 행 수를 커밋된 DB에서 읽는다.
    private int stateCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_user_nickname_states WHERE user_id=?", Integer.class, userId);
    }

    // 시나리오의 해당 날짜 사용자 참가자 수를 커밋된 DB에서 읽는다.
    private int participantCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_room_participants WHERE group_date=? AND user_id=?",
                Integer.class, date, userId);
    }

    // 후속 이벤트가 커밋한 사용자 상태 이름과 버전을 확인한다.
    private void assertState(String nickname, long version) {
        var row = jdbc.queryForMap("SELECT nickname, nickname_version FROM chat_user_nickname_states WHERE user_id=?", userId);
        assertThat(row.get("nickname")).isEqualTo(nickname);
        assertThat(((Number) row.get("nickname_version")).longValue()).isEqualTo(version);
    }

    // 테스트 사용자와 질문에 속한 데이터만 삭제하고 다른 참가자가 있는 방은 보존한다.
    @AfterEach
    void cleanup() {
        jdbc.update("""
                DELETE FROM chat_room_participants WHERE user_id=? AND chat_room_id IN
                (SELECT chat_room_id FROM chat_rooms WHERE group_date=? AND vote_question_id=?)
                """, userId, date, questionId);
        jdbc.update("""
                DELETE FROM chat_rooms WHERE group_date=? AND vote_question_id=?
                AND NOT EXISTS (SELECT 1 FROM chat_room_participants p WHERE p.chat_room_id=chat_rooms.chat_room_id)
                """, date, questionId);
        jdbc.update("""
                DELETE FROM chat_room_group_allocators WHERE group_date=? AND vote_question_id=?
                AND NOT EXISTS (SELECT 1 FROM chat_rooms r WHERE r.group_date=? AND r.vote_question_id=?)
                """, date, questionId, date, questionId);
        jdbc.update("DELETE FROM chat_user_nickname_states WHERE user_id=?", userId);
    }

    // 로컬 전용 그룹 테스트 DB 자격 증명을 Spring 데이터 소스에 연결한다.
    @DynamicPropertySource
    static void groupDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("CHAT_GROUP_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("CHAT_GROUP_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("CHAT_GROUP_TEST_DB_PASSWORD"));
    }
}
