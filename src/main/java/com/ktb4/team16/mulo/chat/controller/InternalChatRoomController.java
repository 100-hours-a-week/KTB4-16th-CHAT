package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.service.GroupChatRoomJoinService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/chat-rooms")
public class InternalChatRoomController {

    private final GroupChatRoomJoinService groupChatRoomJoinService;

    // 그룹 입장 처리를 담당하는 서비스를 주입한다.
    public InternalChatRoomController(
            GroupChatRoomJoinService groupChatRoomJoinService
    ) {
        this.groupChatRoomJoinService = groupChatRoomJoinService;
    }

    @PostMapping("/group/join-or-create")
    public ResponseEntity<GroupChatJoinOrCreateResponse> joinOrCreateGroup(
            @Valid @RequestBody GroupChatJoinOrCreateRequest request
    ) {
        GroupChatRoomJoinService.Result result = groupChatRoomJoinService.joinOrCreate(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }
}
