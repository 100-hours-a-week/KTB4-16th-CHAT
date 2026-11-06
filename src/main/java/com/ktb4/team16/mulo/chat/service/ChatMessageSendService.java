package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatRoomAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatSendAccessException;
import com.ktb4.team16.mulo.chat.exception.ChatSendDiagnostic;
import com.ktb4.team16.mulo.chat.moderation.client.ChatModerationClient;
import com.ktb4.team16.mulo.chat.moderation.client.ChatModerationUnavailableException;
import com.ktb4.team16.mulo.chat.moderation.service.ChatMessageWordFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageSendService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageSendService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<
                    com.ktb4.team16.mulo.chat.delivery.ChatDeliveryLifecycle>
            deliveryLifecycle;

    private final ChatMessageSendTransactionService transactionService;
    private final ChatMessageIdempotencyResolver idempotencyResolver;
    private final ChatMessageSendPrecheckService precheckService;
    private final ChatMessageWordFilter wordFilter;
    private final ChatModerationClient moderationClient;

    public ChatMessageSendService(
            ChatMessageSendTransactionService transactionService,
            ChatMessageIdempotencyResolver idempotencyResolver,
            ChatMessageSendPrecheckService precheckService,
            ChatMessageWordFilter wordFilter,
            ChatModerationClient moderationClient) {
        this.transactionService = transactionService;
        this.idempotencyResolver = idempotencyResolver;
        this.precheckService = precheckService;
        this.wordFilter = wordFilter;
        this.moderationClient = moderationClient;
    }

    /** 상태 검사와 진행 카운터 등록을 묶어 종료와 새 SEND의 경합을 막는다. */
    public void send(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        var state = deliveryLifecycle == null ? null : deliveryLifecycle.getIfAvailable();
        com.ktb4.team16.mulo.chat.delivery.ChatDeliveryLifecycle.SendLease lease = null;
        if (state != null) {
            try {
                lease = state.acquireSend();
            } catch (IllegalStateException error) {
                throw new ChatMessageSendException(
                        ChatErrorCode.CHAT_DELIVERY_UNAVAILABLE, request.clientMessageId());
            }
        }
        try {
            sendAccepted(chatRoomId, userId, request);
        } finally {
            if (lease != null) lease.close();
        }
    }

    /** 획득된 SEND의 moderation·DB commit·AFTER_COMMIT 전파를 완료한다. */
    private void sendAccepted(Long chatRoomId, Long userId, ChatMessageSendRequest request) {
        if (chatRoomId == null || chatRoomId <= 0) {
            ChatSendDiagnostic diagnostic =
                    new ChatSendDiagnostic(
                            ChatSendDiagnostic.Stage.PRECHECK,
                            ChatSendDiagnostic.Reason.INVALID_CHAT_ROOM_ID);
            ChatSendDiagnosticLogger.warn(
                    log,
                    chatRoomId,
                    userId,
                    request.clientMessageId(),
                    "precheck",
                    "INVALID_CHAT_ROOM_ID",
                    null);
            throw new ChatMessageSendException(
                    ChatErrorCode.CHAT_SEND_FORBIDDEN, request.clientMessageId(), diagnostic);
        }
        try {
            boolean requiresModeration =
                    precheckService.requiresModeration(chatRoomId, userId, request);
            if (requiresModeration) {
                ChatMessageWordFilter.Result filterResult = wordFilter.filter(request.content());
                if (filterResult.hasBadWord()) {
                    throw new ChatMessageSendException(
                            ChatErrorCode.PROHIBITED_WORD, request.clientMessageId());
                }
                if (filterResult.isSpam()) {
                    throw new ChatMessageSendException(
                            ChatErrorCode.SPAM_DETECTED, request.clientMessageId());
                }
                if (moderationClient.isToxic(chatRoomId, userId, request.content())) {
                    throw new ChatMessageSendException(
                            ChatErrorCode.MESSAGE_BLOCKED, request.clientMessageId());
                }
            }
            transactionService.send(chatRoomId, userId, request);
        } catch (ChatModerationUnavailableException exception) {
            throw new ChatMessageSendException(
                    ChatErrorCode.MODERATION_UNAVAILABLE, request.clientMessageId());
        } catch (ChatRoomAccessException exception) {
            ChatErrorCode errorCode =
                    switch (exception.getReason()) {
                        case ROOM_EXPIRED -> ChatErrorCode.CHAT_ROOM_EXPIRED;
                        case PEER_NOT_JOINED -> ChatErrorCode.CHAT_PEER_NOT_JOINED;
                        default -> ChatErrorCode.CHAT_SEND_FORBIDDEN;
                    };
            ChatSendDiagnostic diagnostic =
                    exception instanceof ChatSendAccessException sendAccessException
                            ? sendAccessException.getDiagnostic()
                            : null;
            throw new ChatMessageSendException(errorCode, request.clientMessageId(), diagnostic);
        } catch (DataIntegrityViolationException exception) {
            idempotencyResolver.resolveUniqueConflict(chatRoomId, userId, request);
        } catch (DataAccessException exception) {
            throw ChatMessageSendException.persistenceFailure(request.clientMessageId());
        }
    }
}
