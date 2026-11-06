package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.auth.security.AuthenticatedChatUser;
import com.ktb4.team16.mulo.chat.dto.response.*;
import com.ktb4.team16.mulo.chat.service.ChatMessageSyncService;

import jakarta.validation.constraints.Positive;

import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/chat-rooms")
public class ChatMessageSyncController {
    private final ChatMessageSyncService service;

    /** 인증된 사용자 전용 복구 서비스를 연결한다. */
    public ChatMessageSyncController(ChatMessageSyncService service) {
        this.service = service;
    }

    /** 캐시되지 않는 방별 복구 기준점 또는 고정 상한 페이지를 반환한다. */
    @GetMapping("/{chatRoomId}/messages/sync")
    public ResponseEntity<ApiDataResponse<ChatMessageSyncResponse>> sync(
            @PathVariable @Positive Long chatRoomId,
            @RequestParam(required = false) Long afterMessageId,
            @RequestParam(required = false) Long throughMessageId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new ApiDataResponse<>(
                                "채팅 메시지 복구 조회 성공",
                                service.sync(
                                        chatRoomId,
                                        AuthenticatedChatUser.requireUserId(),
                                        afterMessageId,
                                        throughMessageId)));
    }
}
