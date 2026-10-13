package com.ktb4.team16.mulo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_rooms")
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id", nullable = false, updatable = false)
    private Long chatRoomId;

    @Column(name = "match_id", nullable = false, unique = true)
    private Long matchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ChatRoom() {
    }

    private ChatRoom(Long matchId) {
        this.matchId = matchId;
    }

    public static ChatRoom create(Long matchId) {
        return new ChatRoom(matchId);
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
}
