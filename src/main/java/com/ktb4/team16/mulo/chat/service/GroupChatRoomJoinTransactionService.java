package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomGroupAllocator;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.repository.ChatRoomGroupAllocatorRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

@Service
public class GroupChatRoomJoinTransactionService {

    private static final int GROUP_ROOM_CAPACITY = 30;

    private final ChatRoomGroupAllocatorRepository allocatorRepository;
    private final ChatRoomRepository roomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatNicknameStateResolver nicknameResolver;
    private final Clock clock;

    /** 그룹 배정 저장소와 사용자 닉네임 상태 결정을 연결한다. */
    public GroupChatRoomJoinTransactionService(
            ChatRoomGroupAllocatorRepository allocatorRepository,
            ChatRoomRepository roomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatNicknameStateResolver nicknameResolver,
            Clock clock
    ) {
        this.allocatorRepository = allocatorRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.nicknameResolver = nicknameResolver;
        this.clock = clock;
    }

    // 누락된 날짜별 참가자 행의 gap lock을 피하고, 사용자 일일 UNIQUE 제약으로 경합을 최종 조정한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GroupChatRoomJoinService.Result joinOrCreate(GroupChatJoinOrCreateRequest request) {
        validateGroupDate(request.groupDate());
        // 중요: 닉네임 이벤트와 같은 사용자 상태를 참가자·그룹 배정보다 먼저 잠근다.
        var nickname = nicknameResolver.resolveNickname(request.userId(), request.nickname(), request.nicknameVersion());
        validateGroupDate(request.groupDate());
        var existing = participantRepository.findByGroupDateAndUserIdForUpdate(request.groupDate(), request.userId());
        if (existing.isPresent()) {
            var result = resolveExisting(request, existing.get());
            nicknameResolver.discardUnusedFirstState(request.userId(), nickname);
            return result;
        }

        allocatorRepository.ensureGroupRow(request.groupDate(), request.voteQuestionId());
        ChatRoomGroupAllocator allocator = allocatorRepository
                .findByGroupDateAndVoteQuestionIdForUpdate(request.groupDate(), request.voteQuestionId())
                .orElseThrow(() -> new ChatApiException(ChatErrorCode.INTERNAL_SERVER_ERROR));
        validateGroupDate(request.groupDate());

        // 같은 그룹 할당 행 잠금 대기 중 선행 요청이 커밋했을 수 있으므로 배정 전에 다시 확인한다.
        existing = participantRepository.findByGroupDateAndUserIdForUpdate(request.groupDate(), request.userId());
        if (existing.isPresent()) {
            var result = resolveExisting(request, existing.get());
            nicknameResolver.discardUnusedFirstState(request.userId(), nickname);
            return result;
        }

        ChatRoom room = roomRepository
                .findFirstByRoomTypeAndGroupDateAndVoteQuestionIdOrderByRoomSequenceDesc(
                        ChatRoom.GROUP_ROOM_TYPE, request.groupDate(), request.voteQuestionId())
                .filter(lastRoom -> lastRoom.getAdmittedParticipantCount() < GROUP_ROOM_CAPACITY)
                .orElseGet(() -> createNextRoom(request, allocator));

        // 새 배정이 확정된 경우에만 최초 상태와 기존 방의 이름을 함께 맞춘다.
        nicknameResolver.reconcileExistingParticipants(request.userId(), nickname);
        room.admitParticipant();
        participantRepository.saveAndFlush(ChatRoomParticipant.createGroup(
                room, request.userId(), nickname.nickname(), request.groupDate(), request.voteChoice()));
        validateGroupDate(request.groupDate());
        return new GroupChatRoomJoinService.Result(response(room, true), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public GroupChatJoinOrCreateResponse resolveAfterConflict(GroupChatJoinOrCreateRequest request) {
        validateGroupDate(request.groupDate());
        ChatRoomParticipant participant = participantRepository
                .findByGroupDateAndUserIdForUpdate(request.groupDate(), request.userId())
                .orElseThrow(() -> new ChatApiException(ChatErrorCode.INTERNAL_SERVER_ERROR));
        GroupChatRoomJoinService.Result result = resolveExisting(request, participant);
        return result.response();
    }

    private ChatRoom createNextRoom(GroupChatJoinOrCreateRequest request, ChatRoomGroupAllocator allocator) {
        allocator.advanceRoomSequence();
        LocalDate expiresOn = request.groupDate().plusDays(1);
        OffsetDateTime expiresAt = expiresOn.atStartOfDay(ChatTime.KST).toOffsetDateTime();
        return roomRepository.saveAndFlush(ChatRoom.createGroup(
                request.groupDate(), request.voteQuestionId(), allocator.getLastRoomSequence(), expiresAt));
    }

    private GroupChatRoomJoinService.Result resolveExisting(
            GroupChatJoinOrCreateRequest request, ChatRoomParticipant participant
    ) {
        ChatRoom room = participant.getChatRoom();
        validateGroupDate(request.groupDate());
        if (!ChatRoom.GROUP_ROOM_TYPE.equals(room.getRoomType())
                || !request.voteQuestionId().equals(room.getVoteQuestionId())
                || !request.voteChoice().equals(participant.getVoteChoice())) {
            throw new ChatApiException(ChatErrorCode.CHAT_GROUP_ASSIGNMENT_CONFLICT);
        }
        if (participant.getLeftAt() != null) {
            throw new ChatApiException(ChatErrorCode.CHAT_REENTRY_FORBIDDEN);
        }
        return new GroupChatRoomJoinService.Result(response(room, false), false);
    }

    private GroupChatJoinOrCreateResponse response(ChatRoom room, boolean newlyAssigned) {
        return new GroupChatJoinOrCreateResponse(
                room.getChatRoomId(),
                room.getRoomSequence(),
                newlyAssigned,
                room.getAdmittedParticipantCount(),
                (int) participantRepository.countByChatRoomChatRoomIdAndJoinedAtIsNotNullAndLeftAtIsNull(
                        room.getChatRoomId()),
                ChatTime.toOffsetDateTime(room.getExpiresAt())
        );
    }

    private void validateGroupDate(LocalDate groupDate) {
        if (groupDate == null || !groupDate.equals(ChatTime.now(clock).toLocalDate())) {
            throw new ChatApiException(ChatErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
