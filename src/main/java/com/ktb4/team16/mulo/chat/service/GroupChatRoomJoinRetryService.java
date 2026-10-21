package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import org.springframework.stereotype.Service;

@Service
public class GroupChatRoomJoinRetryService {

    private final GroupChatRoomJoinTransactionService transactionService;

    public GroupChatRoomJoinRetryService(GroupChatRoomJoinTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public GroupChatRoomJoinService.Result resolve(GroupChatJoinOrCreateRequest request) {
        return new GroupChatRoomJoinService.Result(transactionService.resolveAfterConflict(request), false);
    }
}
