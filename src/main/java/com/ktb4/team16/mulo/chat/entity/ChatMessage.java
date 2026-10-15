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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(
        name = "chat_messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_messages_user_client_message",
                columnNames = {"user_id", "client_message_id"}
        )
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id", nullable = false, updatable = false)
    private Long chatMessageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "chat_room_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_chat_messages_room")
    )
    private ChatRoom chatRoom;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(Types.CHAR)
    @Column(name = "client_message_id", nullable = false, length = 36)
    private String clientMessageId;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ChatMessage() {
    }

    private ChatMessage(ChatRoom chatRoom, Long userId, String clientMessageId, String content) {
        this.chatRoom = chatRoom;
        this.userId = userId;
        this.clientMessageId = clientMessageId;
        this.content = content;
    }

    public static ChatMessage create(ChatRoom chatRoom, Long userId, String clientMessageId, String content) {
        return new ChatMessage(chatRoom, userId, clientMessageId, content);
    }

    @PrePersist
    private void setCreatedAt() {
        if (createdAt == null) {
            createdAt = ChatTime.now();
        }
    }

    public Long getChatMessageId() {
        return chatMessageId;
    }

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

    public Long getUserId() {
        return userId;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
