package com.ktb4.team16.mulo.chat.repository;

import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import java.util.Optional;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByMatchId(Long matchId);

    boolean existsByMatchId(Long matchId);

    Optional<ChatRoom> findFirstByRoomTypeAndGroupDateAndVoteQuestionIdOrderByRoomSequenceDesc(
            String roomType, LocalDate groupDate, Long voteQuestionId
    );
}
