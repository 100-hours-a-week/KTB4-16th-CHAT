package com.ktb4.team16.mulo.chat.entity;

import com.ktb4.team16.mulo.chat.ChatTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "chat_room_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_room_participants_room_user",
                columnNames = {"chat_room_id", "user_id"}
        )
)
public class ChatRoomParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_participant_id", nullable = false, updatable = false)
    private Long chatRoomParticipantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "chat_room_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_chat_room_participants_room")
    )
    private ChatRoom chatRoom;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "nickname", nullable = false, length = 10)
    private String nickname;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    protected ChatRoomParticipant() {
    }

    private ChatRoomParticipant(ChatRoom chatRoom, Long userId, String nickname) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.nickname = nickname;
    }

    public static ChatRoomParticipant create(ChatRoom chatRoom, Long userId, String nickname) {
        return new ChatRoomParticipant(chatRoom, userId, nickname);
    }

    public void enter() {
        if (joinedAt == null) {
            joinedAt = ChatTime.now();
        }
    }

    public void leave() {
        leftAt = ChatTime.now();
    }

    public Long getChatRoomParticipantId() {
        return chatRoomParticipantId;
    }

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public LocalDateTime getLeftAt() {
        return leftAt;
    }
}
