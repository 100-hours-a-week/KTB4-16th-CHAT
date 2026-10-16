package com.ktb4.team16.mulo.chat.controller;

import com.ktb4.team16.mulo.chat.auth.security.ChatPrincipal;
import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageErrorResponse;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.service.ChatMessageSendService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Controller
public class ChatMessageStompController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatMessageSendService chatMessageSendService;

    public ChatMessageStompController(ChatMessageSendService chatMessageSendService) {
        this.chatMessageSendService = chatMessageSendService;
    }

    @MessageMapping("/chat-rooms/{chatRoomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatRoomId,
            @Valid @Payload ChatMessageSendRequest request,
            ChatPrincipal principal
    ) {
        chatMessageSendService.send(chatRoomId, principal.userId(), request);
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/chat-errors")
    public ChatMessageErrorResponse handleSendError(Exception exception) {
        if (exception instanceof ChatMessageSendException sendException) {
            return new ChatMessageErrorResponse(
                    sendException.getCode(), sendException.getMessage(), sendException.getClientMessageId());
        }
        if (exception instanceof MethodArgumentNotValidException validationException) {
            UUID clientMessageId = extractClientMessageId(validationException);
            boolean tooLong = validationException.getBindingResult().getFieldErrors().stream()
                    .anyMatch(error -> error.getField().equals("content")
                            && error.getCode() != null && error.getCode().equals("Size"));
            if (tooLong) {
                return new ChatMessageErrorResponse(
                        "MESSAGE_TOO_LONG", "메시지는 500자 이하여야 합니다.", clientMessageId);
            }
            return new ChatMessageErrorResponse(
                    "STOMP_ERROR", "메시지 요청 형식이 올바르지 않습니다.", clientMessageId);
        }
        if (exception instanceof MessageConversionException) {
            return new ChatMessageErrorResponse(
                    "STOMP_ERROR", "메시지 요청 형식이 올바르지 않습니다.", extractClientMessageId(exception));
        }
        return new ChatMessageErrorResponse("STOMP_ERROR", "메시지를 전송할 수 없습니다.", null);
    }

    private UUID extractClientMessageId(Exception exception) {
        if (!(exception instanceof MessagingException messagingException)
                || messagingException.getFailedMessage() == null) {
            return null;
        }
        Object payload = messagingException.getFailedMessage().getPayload();
        if (payload instanceof ChatMessageSendRequest request) {
            return request.clientMessageId();
        }
        try {
            String json = payload instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8) : payload.toString();
            JsonNode clientMessageId = OBJECT_MAPPER.readTree(json).path("clientMessageId");
            return clientMessageId == null || !clientMessageId.isString()
                    ? null : UUID.fromString(clientMessageId.asString());
        } catch (RuntimeException parseException) {
            return null;
        }
    }
}
