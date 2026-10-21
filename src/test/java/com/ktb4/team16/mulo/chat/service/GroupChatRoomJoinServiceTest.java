package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class GroupChatRoomJoinServiceTest {

    @Test
    @DisplayName("사용자 날짜별 UNIQUE 경합은 실패 트랜잭션 밖에서 같은 배정을 재조회한다")
    void resolvesDuplicateAssignmentAfterTransactionFailure() {
        GroupChatRoomJoinTransactionService transactionService = mock(GroupChatRoomJoinTransactionService.class);
        GroupChatRoomJoinRetryService retryService = mock(GroupChatRoomJoinRetryService.class);
        GroupChatRoomJoinService service = new GroupChatRoomJoinService(transactionService, retryService);
        GroupChatJoinOrCreateRequest request = new GroupChatJoinOrCreateRequest(
                LocalDate.of(2026, 10, 21), 88L, 17L, "nickname", "A");
        GroupChatRoomJoinService.Result retryResult = new GroupChatRoomJoinService.Result(
                new GroupChatJoinOrCreateResponse(501L, 1, false, 1, 0,
                        OffsetDateTime.of(2026, 10, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9))), false);
        when(transactionService.joinOrCreate(request)).thenThrow(new DataIntegrityViolationException(
                "duplicate assignment",
                new ConstraintViolationException("duplicate", new SQLException(),
                        "uk_chat_room_participants_group_date_user")));
        when(retryService.resolve(request)).thenReturn(retryResult);

        GroupChatRoomJoinService.Result result = service.joinOrCreate(request);

        assertThat(result).isEqualTo(retryResult);
        verify(retryService).resolve(request);
    }
}
