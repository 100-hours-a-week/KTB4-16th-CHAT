package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.dto.response.CreateChatRoomResponse;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ChatRoomCreateService {

    private static final String MATCH_ID_UNIQUE_CONSTRAINT = "uk_chat_rooms_match";

    private final ChatRoomCreateTransactionService transactionService;
    private final ChatRoomCreateRetryService retryService;

    public ChatRoomCreateService(
            ChatRoomCreateTransactionService transactionService,
            ChatRoomCreateRetryService retryService
    ) {
        this.transactionService = transactionService;
        this.retryService = retryService;
    }

    public Result create(CreateChatRoomRequest request) {
        validateDistinctParticipants(request);
        try {
            return transactionService.create(request);
        } catch (DataIntegrityViolationException exception) {
            if (!isMatchIdConflict(exception)) {
                throw exception;
            }
            return retryService.resolve(request);
        }
    }

    private void validateDistinctParticipants(CreateChatRoomRequest request) {
        if (request.participants() == null || request.participants().size() != 2
                || request.participants().get(0) == null || request.participants().get(1) == null
                || request.participants().get(0).userId() == null
                || request.participants().get(1).userId() == null
                || request.participants().get(0).userId().equals(request.participants().get(1).userId())) {
            throw new ChatApiException(ChatErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private boolean isMatchIdConflict(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && isMatchIdConstraint(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private boolean isMatchIdConstraint(String constraintName) {
        return constraintName != null
                && (MATCH_ID_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraintName)
                || constraintName.toLowerCase(java.util.Locale.ROOT)
                .endsWith("." + MATCH_ID_UNIQUE_CONSTRAINT));
    }

    public record Result(CreateChatRoomResponse response, boolean created) {
    }
}
