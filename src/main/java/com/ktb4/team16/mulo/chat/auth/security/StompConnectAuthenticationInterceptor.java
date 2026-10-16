package com.ktb4.team16.mulo.chat.auth.security;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.exception.BackendAuthException;
import com.ktb4.team16.mulo.chat.auth.exception.StompAuthenticationException;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import com.ktb4.team16.mulo.chat.auth.session.WebSocketSessionRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class StompConnectAuthenticationInterceptor implements ChannelInterceptor {

    private static final Pattern MESSAGE_DESTINATION =
            Pattern.compile("/app/chat-rooms/[0-9]+/messages");
    private static final List<String> ALLOWED_SUBSCRIPTIONS = List.of(
            "/user/queue/chat-messages", "/user/queue/chat-events", "/user/queue/chat-errors");

    private final BackendTokenVerifier tokenVerifier;
    private final WebSocketSessionRegistry sessionRegistry;
    private final Clock clock;

    public StompConnectAuthenticationInterceptor(
            BackendTokenVerifier tokenVerifier,
            WebSocketSessionRegistry sessionRegistry,
            Clock clock
    ) {
        this.tokenVerifier = tokenVerifier;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            throw StompAuthenticationException.protocolViolation();
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            sessionRegistry.disconnect(accessor.getSessionId(), accessor.getReceipt());
            return message;
        }
        if (!sessionRegistry.isAuthenticated(accessor.getSessionId())) {
            throw StompAuthenticationException.unauthorized();
        }
        if (!(accessor.getUser() instanceof ChatPrincipal)) {
            throw StompAuthenticationException.unauthorized();
        }
        if (command == StompCommand.SEND && isAllowedMessageDestination(accessor.getDestination())) {
            return message;
        }
        if (command == StompCommand.SUBSCRIBE
                && ALLOWED_SUBSCRIPTIONS.contains(accessor.getDestination())) {
            return message;
        }
        throw StompAuthenticationException.protocolViolation();
    }

    private boolean isAllowedMessageDestination(String destination) {
        if (destination == null || !MESSAGE_DESTINATION.matcher(destination).matches()) {
            return false;
        }
        String roomId = destination.substring("/app/chat-rooms/".length(), destination.length() - "/messages".length());
        try {
            return Long.parseLong(roomId) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void authenticate(StompHeaderAccessor accessor) {
        if (accessor.getUser() != null || sessionRegistry.isAuthenticated(accessor.getSessionId())) {
            throw StompAuthenticationException.protocolViolation();
        }
        List<String> authorizationHeaders = accessor.getNativeHeader("Authorization");
        if (authorizationHeaders == null || authorizationHeaders.size() != 1) {
            throw StompAuthenticationException.unauthorized();
        }
        String authorization = authorizationHeaders.getFirst();
        String accessToken = extractBearerToken(authorization);
        BackendAuthResponse response;
        try {
            response = tokenVerifier.verify(accessToken);
        } catch (BackendAuthException exception) {
            if (exception.getStatus().is5xxServerError() || exception.getStatus().is4xxClientError()
                    && exception.getStatus().value() != 401 && exception.getStatus().value() != 403) {
                throw StompAuthenticationException.authServiceUnavailable();
            }
            throw StompAuthenticationException.unauthorized();
        } catch (RuntimeException exception) {
            throw StompAuthenticationException.authServiceUnavailable();
        }
        if (response == null || response.userId() == null || response.userId() <= 0 || response.expiresAt() == null) {
            throw StompAuthenticationException.authServiceUnavailable();
        }
        Instant expiresAt = response.expiresAt().toInstant();
        if (!expiresAt.isAfter(clock.instant())) {
            throw StompAuthenticationException.unauthorized();
        }
        ChatPrincipal principal = new ChatPrincipal(response.userId());
        accessor.setUser(principal);
        sessionRegistry.authenticate(accessor.getSessionId(), expiresAt);
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw StompAuthenticationException.unauthorized();
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw StompAuthenticationException.unauthorized();
        }
        return token;
    }
}
