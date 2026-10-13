package com.ktb4.team16.mulo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

@Entity
@Table(name = "chat_rooms")
public class ChatRoom {

    private static final ZoneId CHAT_TIME_ZONE = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id", nullable = false, updatable = false)
    private Long chatRoomId;

    @Column(name = "match_id", nullable = false, unique = true)
    private Long matchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected ChatRoom() {
    }

    private ChatRoom(Long matchId, OffsetDateTime expiresAt) {
        this.matchId = matchId;
        this.expiresAt = Objects.requireNonNull(expiresAt)
                .atZoneSameInstant(CHAT_TIME_ZONE)
                .toLocalDateTime();
    }

    public static ChatRoom create(Long matchId, OffsetDateTime expiresAt) {
        return new ChatRoom(matchId, expiresAt);
    }

    @PrePersist
    private void setCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getChatRoomId() {
        return chatRoomId;
    }

    public Long getMatchId() {
        return matchId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
