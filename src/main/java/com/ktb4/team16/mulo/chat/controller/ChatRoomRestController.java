package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.auth.security.AuthenticatedChatUser;
import com.ktb4.team16.mulo.chat.dto.response.ApiDataResponse;
import com.ktb4.team16.mulo.chat.dto.response.ApiMessageResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageHistoryResponse;
import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.service.ChatRoomRestService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/chat-rooms")
public class ChatRoomRestController {

    private final ChatRoomRestService chatRoomRestService;

    public ChatRoomRestController(ChatRoomRestService chatRoomRestService) {
        this.chatRoomRestService = chatRoomRestService;
    }

    @PostMapping("/{chatRoomId}/members")
    public ApiMessageResponse enter(@PathVariable @Positive Long chatRoomId) {
        chatRoomRestService.enter(chatRoomId, AuthenticatedChatUser.requireUserId());
        return new ApiMessageResponse("채팅방에 입장했습니다.");
    }

    @GetMapping("/{chatRoomId}")
    public ApiDataResponse<ChatRoomDetailResponse> getDetail(@PathVariable @Positive Long chatRoomId) {
        return new ApiDataResponse<>("채팅방 정보 조회 성공",
                chatRoomRestService.getDetail(chatRoomId, AuthenticatedChatUser.requireUserId()));
    }

    @GetMapping("/{chatRoomId}/messages")
    public ApiDataResponse<ChatMessageHistoryResponse> getHistory(
            @PathVariable @Positive Long chatRoomId,
            @RequestParam(required = false) String cursor
    ) {
        return new ApiDataResponse<>("채팅 메시지 내역 조회 성공",
                chatRoomRestService.getHistory(chatRoomId, AuthenticatedChatUser.requireUserId(), cursor));
    }

    @PostMapping("/{chatRoomId}/leave")
    public ApiMessageResponse leave(@PathVariable @Positive Long chatRoomId) {
        chatRoomRestService.leave(chatRoomId, AuthenticatedChatUser.requireUserId());
        return new ApiMessageResponse("채팅방에서 퇴장했습니다.");
    }
}
