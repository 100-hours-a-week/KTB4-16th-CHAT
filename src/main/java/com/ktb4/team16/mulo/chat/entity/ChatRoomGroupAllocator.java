package com.ktb4.team16.mulo.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;

@Entity
@Table(name = "chat_room_group_allocators", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_room_group_allocators_date_question",
        columnNames = {"group_date", "vote_question_id"}
))
public class ChatRoomGroupAllocator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_group_allocator_id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "group_date", nullable = false)
    private LocalDate groupDate;

    @Column(name = "vote_question_id", nullable = false)
    private Long voteQuestionId;

    @Column(name = "last_room_sequence", nullable = false)
    private int lastRoomSequence;

    protected ChatRoomGroupAllocator() {
    }

    public void advanceRoomSequence() {
        lastRoomSequence++;
    }

    public int getLastRoomSequence() {
        return lastRoomSequence;
    }
}
