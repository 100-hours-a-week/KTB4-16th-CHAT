package com.ktb4.team16.mulo.chat.auth.security;

import com.ktb4.team16.mulo.chat.auth.exception.StompAuthenticationException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

public class SafeStompSubProtocolErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable exception) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(resolveSafeCode(exception));
        accessor.setLeaveMutable(true);
        if (clientMessage != null) {
            StompHeaderAccessor clientAccessor = MessageHeaderAccessor.getAccessor(
                    clientMessage, StompHeaderAccessor.class
            );
            if (clientAccessor != null && clientAccessor.getReceipt() != null) {
                accessor.setReceiptId(clientAccessor.getReceipt());
            }
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private String resolveSafeCode(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof StompAuthenticationException stompException) {
                return stompException.getCode();
            }
            current = current.getCause();
        }
        return "STOMP_ERROR";
    }
}
