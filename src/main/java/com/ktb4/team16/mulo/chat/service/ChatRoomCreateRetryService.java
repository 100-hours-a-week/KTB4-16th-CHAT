package com.ktb4.team16.mulo.chat.service;

import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomCreateRetryService {

    private final ChatRoomCreateTransactionService transactionService;

    public ChatRoomCreateRetryService(ChatRoomCreateTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChatRoomCreateService.Result resolve(CreateChatRoomRequest request) {
        return transactionService.resolveExisting(request);
    }
}
