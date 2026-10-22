package com.ktb4.team16.mulo.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.service.ChatRoomCreateService;
import com.ktb4.team16.mulo.chat.service.GroupChatRoomJoinService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InternalChatRoomControllerTest {

    @Test
    @DisplayName("새 그룹 배정은 201과 배정 상태를 반환한다")
    void returnsCreatedForNewGroupAssignment() {
        ChatRoomCreateService directService = mock(ChatRoomCreateService.class);
        GroupChatRoomJoinService groupService = mock(GroupChatRoomJoinService.class);
        InternalChatRoomController controller = new InternalChatRoomController(directService, groupService);
        GroupChatJoinOrCreateRequest request = new GroupChatJoinOrCreateRequest(
                java.time.LocalDate.of(2026, 10, 21), 88L, 17L, "nickname", "A");
        GroupChatJoinOrCreateResponse response = new GroupChatJoinOrCreateResponse(
                501L, 1, true, 1, 0, OffsetDateTime.of(2026, 10, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        when(groupService.joinOrCreate(request)).thenReturn(new GroupChatRoomJoinService.Result(response, true));

        var result = controller.joinOrCreateGroup(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    @DisplayName("멱등한 그룹 배정 재요청은 200과 같은 방을 반환한다")
    void returnsOkForExistingGroupAssignment() {
        ChatRoomCreateService directService = mock(ChatRoomCreateService.class);
        GroupChatRoomJoinService groupService = mock(GroupChatRoomJoinService.class);
        InternalChatRoomController controller = new InternalChatRoomController(directService, groupService);
        GroupChatJoinOrCreateRequest request = new GroupChatJoinOrCreateRequest(
                java.time.LocalDate.of(2026, 10, 21), 88L, 17L, "nickname", "A");
        GroupChatJoinOrCreateResponse response = new GroupChatJoinOrCreateResponse(
                501L, 1, false, 1, 0, OffsetDateTime.of(2026, 10, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        when(groupService.joinOrCreate(request)).thenReturn(new GroupChatRoomJoinService.Result(response, false));

        var result = controller.joinOrCreateGroup(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(response);
    }
}
