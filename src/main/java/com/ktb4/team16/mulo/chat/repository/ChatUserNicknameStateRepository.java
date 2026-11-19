package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatUserNicknameState;
import com.ktb4.team16.mulo.chat.service.ChatNicknameVersionCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChatUserNicknameStateRepository {
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    /** JDBC 최초 저장과 JPA 잠금 조회에 사용할 기존 트랜잭션 접근을 구성한다. */
    public ChatUserNicknameStateRepository(JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    /** 최초 상태를 확보하고 사용자별 변경을 직렬화할 DB 행 잠금을 획득한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedNicknameState ensureAndLock(
            ChatNicknameVersionCommand command, LocalDateTime appliedAt) {
        return ensureAndLock(command.userId(), command.nickname(), command.nicknameVersion(), appliedAt);
    }

    /** 버전 이벤트 ID 없이 방 요청의 첫 상태를 확보하고 사용자 행을 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedNicknameState ensureAndLock(
            Long userId, String nickname, long nicknameVersion, LocalDateTime appliedAt) {
        boolean created = Boolean.TRUE.equals(jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> insertIfAbsent(
                        connection, userId, nickname, nicknameVersion, appliedAt)));
        ChatUserNicknameState state = entityManager.find(
                ChatUserNicknameState.class, userId, LockModeType.PESSIMISTIC_WRITE);
        return new LockedNicknameState(state, created);
    }

    /** 요청 버전이 없는 경로에서 이미 존재하는 상태만 잠그고 읽는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ChatUserNicknameState> findByUserIdForUpdate(Long userId) {
        return Optional.ofNullable(entityManager.find(
                ChatUserNicknameState.class, userId, LockModeType.PESSIMISTIC_WRITE));
    }

    /** 현재 트랜잭션이 임시로 만든 첫 상태를 제거한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteByUserId(Long userId) {
        ChatUserNicknameState state = entityManager.find(
                ChatUserNicknameState.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (state != null) {
            entityManager.remove(state);
        }
    }

    /** 중복 PK만 저장 지점으로 되돌리고 다른 SQL 실패는 원래 트랜잭션에 전파한다. */
    private boolean insertIfAbsent(
            Connection connection, Long userId, String nickname,
            long nicknameVersion, LocalDateTime appliedAt)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_user_nickname_states
                    (user_id, nickname, nickname_version, updated_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, nickname);
            statement.setLong(3, nicknameVersion);
            statement.setTimestamp(4, Timestamp.valueOf(appliedAt));
            statement.executeUpdate();
            connection.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 1062 || !"23000".equals(exception.getSQLState())) {
                throw exception;
            }
            // 중요: 이 테이블의 유일 제약은 user_id PK다. 기존 상태는 덮어쓰지 않는다.
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            return false;
        }
    }

    /** 최초 생성 여부를 보존해 첫 입력을 중복으로 잘못 생략하지 않도록 한다. */
    public record LockedNicknameState(ChatUserNicknameState state, boolean created) {
    }
}
