package com.ktb4.team16.mulo.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionCommand;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@Transactional
class ChatUserNicknameStateRepositoryDatabaseIntegrationTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChatUserNicknameStateRepository states;
    @Autowired private EntityManager entityManager;

    /** 참가자가 없어도 사용자별 닉네임과 버전을 저장할 공간이 있는지 확인한다. */
    @Test
    void stateTableCanStoreAUserWithoutParticipants() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'chat_user_nickname_states'
                """, Integer.class)).isEqualTo(1);
        jdbcTemplate.update("""
                INSERT INTO chat_user_nickname_states
                    (user_id, nickname, nickname_version, updated_at)
                VALUES (900000000001, '여름밤', 8, '2026-11-09 12:00:00')
                """);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT nickname_version FROM chat_user_nickname_states WHERE user_id = 900000000001
                """, Long.class)).isEqualTo(8L);
    }

    /** 최초 저장과 재수신을 구분하고 입력으로 기존 상태를 덮어쓰지 않는지 검증한다. */
    @Test
    void firstMessageCreatesStateAndExistingStateIsPreserved() {
        long userId = 900000000002L;
        LocalDateTime firstTime = LocalDateTime.of(2026, 11, 9, 12, 0);
        var first = states.ensureAndLock(
                new ChatNicknameVersionCommand(userId, "여름밤", 8, UUID.randomUUID()), firstTime);
        assertThat(first.created()).isTrue();
        assertThat(first.state().getNickname()).isEqualTo("여름밤");
        assertThat(first.state().getNicknameVersion()).isEqualTo(8L);
        assertThat(first.state().getUpdatedAt()).isEqualTo(firstTime);

        var existing = states.ensureAndLock(
                new ChatNicknameVersionCommand(userId, "다른이름", 9, UUID.randomUUID()),
                firstTime.plusHours(1));
        assertThat(existing.created()).isFalse();
        assertThat(existing.state().getNickname()).isEqualTo("여름밤");
        assertThat(existing.state().getNicknameVersion()).isEqualTo(8L);
        // 중복 INSERT 예외 후에도 같은 트랜잭션에서 정상적인 갱신과 flush가 가능해야 한다.
        existing.state().apply("가을밤", 9, firstTime.plusHours(1));
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT nickname FROM chat_user_nickname_states WHERE user_id = ?
                """, String.class, userId)).isEqualTo("가을밤");
    }
}
