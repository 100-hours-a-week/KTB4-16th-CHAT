package com.ktb4.team16.mulo.chat.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

@Component
public class WebSocketSessionRegistry {

    private static final Duration DISCONNECT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectProvider<TaskScheduler> taskSchedulerProvider;
    private final Clock clock;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AuthenticatedSession> authenticatedSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingDisconnect> pendingDisconnects = new ConcurrentHashMap<>();

    public WebSocketSessionRegistry(
            @Qualifier("messageBrokerTaskScheduler") ObjectProvider<TaskScheduler> taskSchedulerProvider,
            Clock clock
    ) {
        this.taskSchedulerProvider = taskSchedulerProvider;
        this.clock = clock;
    }

    public WebSocketHandlerDecoratorFactory decoratorFactory() {
        return handler -> new WebSocketHandlerDecorator(handler) {
            private final Map<String, WebSocketSession> decoratedSessions = new ConcurrentHashMap<>();

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                WebSocketSession decoratedSession = decorate(session);
                sessions.put(session.getId(), decoratedSession);
                try {
                    super.afterConnectionEstablished(decoratedSession);
                } catch (Exception exception) {
                    remove(session.getId());
                    decoratedSessions.remove(session.getId(), decoratedSession);
                    throw exception;
                }
            }

            @Override
            public void handleMessage(WebSocketSession session, org.springframework.web.socket.WebSocketMessage<?> message)
                    throws Exception {
                super.handleMessage(decorate(session), message);
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                super.handleTransportError(decorate(session), exception);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                remove(session.getId());
                WebSocketSession decoratedSession = decoratedSessions.remove(session.getId());
                super.afterConnectionClosed(
                        decoratedSession == null ? session : decoratedSession,
                        closeStatus
                );
            }

            private WebSocketSession decorate(WebSocketSession session) {
                return decoratedSessions.computeIfAbsent(session.getId(), ignored ->
                        new WebSocketSessionDecorator(session) {
                            @Override
                            public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message)
                                    throws IOException {
                                super.sendMessage(message);
                                handleOutboundMessage(getId(), message);
                            }
                        });
            }
        };
    }

    public synchronized void authenticate(String sessionId, Instant expiresAt) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen() || !expiresAt.isAfter(clock.instant())) {
            throw StompAuthenticationException.unauthorized();
        }
        AuthenticatedSession current = new AuthenticatedSession(session, expiresAt);
        if (authenticatedSessions.putIfAbsent(sessionId, current) != null) {
            throw StompAuthenticationException.protocolViolation();
        }
        ScheduledFuture<?> expiryTask;
        try {
            expiryTask = taskSchedulerProvider.getObject().schedule(() -> expire(sessionId, current), expiresAt);
        } catch (RuntimeException exception) {
            authenticatedSessions.remove(sessionId, current);
            throw StompAuthenticationException.authServiceUnavailable();
        }
        if (expiryTask == null) {
            authenticatedSessions.remove(sessionId, current);
            throw StompAuthenticationException.authServiceUnavailable();
        }
        current.setExpiryTask(expiryTask);
    }

    public boolean isAuthenticated(String sessionId) {
        AuthenticatedSession session = authenticatedSessions.get(sessionId);
        return session != null && session.expiresAt().isAfter(clock.instant()) && session.session().isOpen();
    }

    public synchronized void disconnect(String sessionId, String receiptId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || pendingDisconnects.containsKey(sessionId)) {
            return;
        }
        AuthenticatedSession authenticated = authenticatedSessions.remove(sessionId);
        if (authenticated != null && authenticated.expiryTask() != null) {
            authenticated.expiryTask().cancel(false);
        }

        PendingDisconnect pending = new PendingDisconnect(receiptId);
        pendingDisconnects.put(sessionId, pending);
        try {
            ScheduledFuture<?> closeTask = taskSchedulerProvider.getObject().schedule(
                    () -> closeAfterDisconnect(sessionId, pending),
                    clock.instant().plus(DISCONNECT_CLOSE_TIMEOUT)
            );
            if (closeTask == null) {
                throw new IllegalStateException("Unable to schedule WebSocket close.");
            }
            pending.setCloseTask(closeTask);
        } catch (RuntimeException exception) {
            closeAfterDisconnect(sessionId, pending);
        }
    }

    private void expire(String sessionId, AuthenticatedSession expected) {
        if (!authenticatedSessions.remove(sessionId, expected)) {
            return;
        }
        try {
            if (expected.session().isOpen()) {
                expected.session().close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException ignored) {
            // 전송 계층이 이미 실패했더라도 세션 레지스트리에서는 제거합니다.
        } finally {
            sessions.remove(sessionId, expected.session());
        }
    }

    private void handleOutboundMessage(String sessionId, org.springframework.web.socket.WebSocketMessage<?> message) {
        if (!(message instanceof TextMessage textMessage)) {
            return;
        }
        PendingDisconnect pending = pendingDisconnects.get(sessionId);
        if (pending != null && pending.receiptId() != null
                && isStompReceipt(textMessage.getPayload(), pending.receiptId())) {
            closeAfterDisconnect(sessionId, pending);
        }
    }

    private boolean isStompReceipt(String frame, String expectedReceiptId) {
        int headerEnd = frame.indexOf("\n\n");
        if (headerEnd < 0) {
            return false;
        }
        String[] headers = frame.substring(0, headerEnd).split("\n");
        if (headers.length == 0 || !"RECEIPT".equals(headers[0].replace("\r", ""))) {
            return false;
        }
        String expectedHeader = "receipt-id:" + escapeStompHeader(expectedReceiptId);
        for (int index = 1; index < headers.length; index++) {
            if (expectedHeader.equals(headers[index].replace("\r", ""))) {
                return true;
            }
        }
        return false;
    }

    private String escapeStompHeader(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace(":", "\\c");
    }

    private void closeAfterDisconnect(String sessionId, PendingDisconnect expected) {
        WebSocketSession session;
        synchronized (this) {
            if (!pendingDisconnects.remove(sessionId, expected)) {
                return;
            }
            if (expected.closeTask() != null) {
                expected.closeTask().cancel(false);
            }
            session = sessions.get(sessionId);
        }
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException ignored) {
                remove(sessionId);
            }
        }
    }

    private synchronized void remove(String sessionId) {
        sessions.remove(sessionId);
        AuthenticatedSession authenticated = authenticatedSessions.remove(sessionId);
        if (authenticated != null && authenticated.expiryTask() != null) {
            authenticated.expiryTask().cancel(false);
        }
        PendingDisconnect pending = pendingDisconnects.remove(sessionId);
        if (pending != null && pending.closeTask() != null) {
            pending.closeTask().cancel(false);
        }
    }

    private static final class PendingDisconnect {
        private final String receiptId;
        private volatile ScheduledFuture<?> closeTask;

        private PendingDisconnect(String receiptId) {
            this.receiptId = receiptId;
        }

        private String receiptId() {
            return receiptId;
        }

        private ScheduledFuture<?> closeTask() {
            return closeTask;
        }

        private void setCloseTask(ScheduledFuture<?> task) {
            this.closeTask = task;
        }
    }

    private static final class AuthenticatedSession {
        private final WebSocketSession session;
        private final Instant expiresAt;
        private volatile ScheduledFuture<?> expiryTask;

        private AuthenticatedSession(WebSocketSession session, Instant expiresAt) {
            this.session = session;
            this.expiresAt = expiresAt;
        }

        private WebSocketSession session() {
            return session;
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private ScheduledFuture<?> expiryTask() {
            return expiryTask;
        }

        private void setExpiryTask(ScheduledFuture<?> task) {
            this.expiryTask = task;
        }
    }
}
