package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageSendService {

    private final ChatMessageSendTransactionService transactionService;
    private final ChatMessageIdempotencyResolver idempotencyResolver;

    public ChatMessageSendService(
            ChatMessageSendTransactionService transactionService,
            ChatMessageIdempotencyResolver idempotencyResolver
    ) {
        this.transactionService = transactionService;
        this.idempotencyResolver = idempotencyResolver;
    }

    public void send(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        if (chatRoomId == null || chatRoomId <= 0) {
            throw new ChatMessageSendException(ChatErrorCode.CHAT_SEND_FORBIDDEN, request.clientMessageId());
        }
        try {
            transactionService.send(chatRoomId, userId, request);
        } catch (ChatRoomAccessException exception) {
            if (exception.getReason() == ChatRoomAccessException.Reason.ROOM_EXPIRED) {
                throw new ChatMessageSendException(ChatErrorCode.CHAT_ROOM_EXPIRED, request.clientMessageId());
            }
            throw new ChatMessageSendException(ChatErrorCode.CHAT_SEND_FORBIDDEN, request.clientMessageId());
        } catch (DataIntegrityViolationException exception) {
            idempotencyResolver.resolveUniqueConflict(chatRoomId, userId, request);
        } catch (DataAccessException exception) {
            throw ChatMessageSendException.persistenceFailure(request.clientMessageId());
        }
    }
}
