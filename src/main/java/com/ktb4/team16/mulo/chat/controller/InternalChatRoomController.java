package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.dto.response.CreateChatRoomResponse;
import com.ktb4.team16.mulo.chat.service.ChatRoomCreateService;
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

    private final ChatRoomCreateService chatRoomCreateService;

    public InternalChatRoomController(ChatRoomCreateService chatRoomCreateService) {
        this.chatRoomCreateService = chatRoomCreateService;
    }

    @PostMapping
    public ResponseEntity<CreateChatRoomResponse> create(@Valid @RequestBody CreateChatRoomRequest request) {
        ChatRoomCreateService.Result result = chatRoomCreateService.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }
}
