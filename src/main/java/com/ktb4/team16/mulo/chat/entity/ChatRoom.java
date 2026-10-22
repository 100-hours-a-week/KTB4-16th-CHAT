package com.ktb4.team16.mulo.chat.entity;

import com.ktb4.team16.mulo.chat.ChatTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "chat_rooms", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_rooms_group_sequence",
        columnNames = {"group_date", "vote_question_id", "room_sequence"}
))
public class ChatRoom {

    public static final String DIRECT_ROOM_TYPE = "DIRECT";
    public static final String GROUP_ROOM_TYPE = "GROUP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id", nullable = false, updatable = false)
    private Long chatRoomId;

    @Column(name = "match_id", unique = true)
    private Long matchId;

    @Column(name = "room_type", nullable = false, length = 16)
    private String roomType;

    @Column(name = "group_date")
    private LocalDate groupDate;

    @Column(name = "vote_question_id")
    private Long voteQuestionId;

    @Column(name = "room_sequence")
    private Integer roomSequence;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "admitted_participant_count", nullable = false)
    private int admittedParticipantCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected ChatRoom() {
    }

    private ChatRoom(Long matchId, OffsetDateTime expiresAt) {
        this.matchId = matchId;
        this.roomType = DIRECT_ROOM_TYPE;
        this.capacity = 2;
        this.admittedParticipantCount = 2;
        this.expiresAt = Objects.requireNonNull(expiresAt)
                .atZoneSameInstant(ChatTime.KST)
                .toLocalDateTime();
    }

    public static ChatRoom create(Long matchId, OffsetDateTime expiresAt) {
        return new ChatRoom(matchId, expiresAt);
    }

    private ChatRoom(LocalDate groupDate, Long voteQuestionId, int roomSequence, OffsetDateTime expiresAt) {
        this.roomType = GROUP_ROOM_TYPE;
        this.groupDate = Objects.requireNonNull(groupDate);
        this.voteQuestionId = Objects.requireNonNull(voteQuestionId);
        this.roomSequence = roomSequence;
        this.capacity = 30;
        this.admittedParticipantCount = 0;
        this.expiresAt = Objects.requireNonNull(expiresAt)
                .atZoneSameInstant(ChatTime.KST)
                .toLocalDateTime();
    }

    public static ChatRoom createGroup(
            LocalDate groupDate, Long voteQuestionId, int roomSequence, OffsetDateTime expiresAt
    ) {
        return new ChatRoom(groupDate, voteQuestionId, roomSequence, expiresAt);
    }

    public void admitParticipant() {
        if (admittedParticipantCount >= capacity) {
            throw new IllegalStateException("채팅방 정원이 가득 찼습니다.");
        }
        admittedParticipantCount++;
    }

    @PrePersist
    private void setCreatedAt() {
        if (createdAt == null) {
            createdAt = ChatTime.now();
        }
    }

    public Long getChatRoomId() {
        return chatRoomId;
    }

    public Long getMatchId() {
        return matchId;
    }

    public String getRoomType() { return roomType; }

    public LocalDate getGroupDate() { return groupDate; }

    public Long getVoteQuestionId() { return voteQuestionId; }

    public Integer getRoomSequence() { return roomSequence; }

    public int getCapacity() { return capacity; }

    public int getAdmittedParticipantCount() { return admittedParticipantCount; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
