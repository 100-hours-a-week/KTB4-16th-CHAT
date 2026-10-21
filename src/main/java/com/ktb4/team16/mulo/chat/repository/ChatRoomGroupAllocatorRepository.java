package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatRoomGroupAllocator;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomGroupAllocatorRepository extends JpaRepository<ChatRoomGroupAllocator, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO chat_room_group_allocators (group_date, vote_question_id, last_room_sequence)
            VALUES (:groupDate, :voteQuestionId, 0)
            ON DUPLICATE KEY UPDATE last_room_sequence = last_room_sequence
            """, nativeQuery = true)
    void ensureGroupRow(@Param("groupDate") LocalDate groupDate, @Param("voteQuestionId") Long voteQuestionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select allocator
            from ChatRoomGroupAllocator allocator
            where allocator.groupDate = :groupDate
              and allocator.voteQuestionId = :voteQuestionId
            """)
    Optional<ChatRoomGroupAllocator> findByGroupDateAndVoteQuestionIdForUpdate(
            @Param("groupDate") LocalDate groupDate,
            @Param("voteQuestionId") Long voteQuestionId
    );
}
