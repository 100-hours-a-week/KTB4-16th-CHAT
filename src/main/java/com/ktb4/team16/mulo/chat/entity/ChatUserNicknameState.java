package com.ktb4.team16.mulo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 방 참가 여부와 관계없이 마지막으로 적용한 사용자별 닉네임을 보관한다. */
@Entity
@Table(name = "chat_user_nickname_states")
public class ChatUserNicknameState {
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String nickname;

    @Column(name = "nickname_version", nullable = false)
    private long nicknameVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA가 저장된 상태를 복원할 때 사용한다. */
    protected ChatUserNicknameState() {
    }

    /** 버전 비교를 통과한 이름과 버전, 적용 시각을 함께 갱신한다. */
    public void apply(String nickname, long nicknameVersion, LocalDateTime appliedAt) {
        this.nickname = nickname;
        this.nicknameVersion = nicknameVersion;
        this.updatedAt = appliedAt;
    }

    /** 상태의 소유 사용자 ID를 반환한다. */
    public Long getUserId() {
        return userId;
    }

    /** 마지막 적용 버전의 이름을 반환한다. */
    public String getNickname() {
        return nickname;
    }

    /** 마지막으로 적용한 BE 버전을 반환한다. */
    public long getNicknameVersion() {
        return nicknameVersion;
    }

    /** 채팅 서버에서 상태를 적용한 시각을 반환한다. */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
