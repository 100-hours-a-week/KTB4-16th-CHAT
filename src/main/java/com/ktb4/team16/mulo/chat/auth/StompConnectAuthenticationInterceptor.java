package com.ktb4.team16.mulo.chat.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Component
public class StompConnectAuthenticationInterceptor implements ChannelInterceptor {

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
        throw StompAuthenticationException.protocolViolation();
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
