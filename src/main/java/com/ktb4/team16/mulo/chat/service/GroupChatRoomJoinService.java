package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class GroupChatRoomJoinService {

    private static final String GROUP_ASSIGNMENT_CONSTRAINT = "uk_chat_room_participants_group_date_user";

    private final GroupChatRoomJoinTransactionService transactionService;
    private final GroupChatRoomJoinRetryService retryService;

    public GroupChatRoomJoinService(
            GroupChatRoomJoinTransactionService transactionService,
            GroupChatRoomJoinRetryService retryService
    ) {
        this.transactionService = transactionService;
        this.retryService = retryService;
    }

    public Result joinOrCreate(GroupChatJoinOrCreateRequest request) {
        try {
            return transactionService.joinOrCreate(request);
        } catch (DataIntegrityViolationException exception) {
            if (isGroupAssignmentConflict(exception)) {
                return retryService.resolve(request);
            }
            throw exception;
        }
    }

    private boolean isGroupAssignmentConflict(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                return constraint != null
                        && (GROUP_ASSIGNMENT_CONSTRAINT.equalsIgnoreCase(constraint)
                        || constraint.toLowerCase(java.util.Locale.ROOT)
                        .endsWith("." + GROUP_ASSIGNMENT_CONSTRAINT));
            }
            cause = cause.getCause();
        }
        return false;
    }

    public record Result(GroupChatJoinOrCreateResponse response, boolean created) {
    }
}
