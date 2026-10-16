package com.ktb4.team16.mulo.chat.auth.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.exception.BackendAuthException;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.junit.jupiter.api.BeforeEach;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.auth.backend.url=http://backend.test",
        "chat.auth.backend.internal-api-key=test-internal-key",
        "chat.websocket.allowed-origins=http://localhost"
})
@Import(StompConnectAuthenticationIntegrationTest.SubscriptionTestConfiguration.class)
class StompConnectAuthenticationIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionEvents subscriptionEvents;

    @BeforeEach
    void clearRecordedSubscriptions() {
        subscriptionEvents.clear();
    }

    @Test
    void connectsWithVerifiedPrincipalAndRevalidatesOnReconnect() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));

        StompSocket first = connect("Authorization:Bearer valid-token\n");
        assertTrue(first.awaitFrame());
        assertTrue(first.frames().stream().anyMatch(frame -> frame.contains("CONNECTED")
                && frame.contains("user-name:17")));
        first.close();

        StompSocket second = connect("Authorization:Bearer valid-token\n");
        assertTrue(second.awaitFrame());
        assertTrue(second.frames().stream().anyMatch(frame -> frame.contains("CONNECTED")
                && frame.contains("user-name:17")));
        second.close();
        verify(tokenVerifier, org.mockito.Mockito.times(2)).verify("valid-token");
    }

    @Test
    void rejectsMissingMalformedAndInvalidCredentialsWithErrorAndClose() throws Exception {
        when(tokenVerifier.verify("tampered-token")).thenThrow(BackendAuthException.unauthorized());
        assertErrorAndClose(connect(""));
        assertErrorAndClose(connect("Authorization:Basic abc\n"));
        assertErrorAndClose(connect("Authorization:Bearer one\nAuthorization:Bearer two\n"));
        StompSocket invalid = connect("Authorization:Bearer tampered-token\n");
        assertErrorAndClose(invalid);
        assertTrue(invalid.frames().stream().anyMatch(frame -> frame.contains("UNAUTHORIZED")),
                () -> String.join("\n", invalid.frames()));
    }

    @Test
    void rejectsExpiredBackendResponseAndUpstreamFailureWithoutLeakingDetails() throws Exception {
        when(tokenVerifier.verify("expired-token")).thenReturn(new BackendAuthResponse(
                17L, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1)
        ));
        when(tokenVerifier.verify("backend-down"))
                .thenThrow(BackendAuthException.unavailable());
        when(tokenVerifier.verify("backend-forbidden"))
                .thenThrow(BackendAuthException.forbidden());
        when(tokenVerifier.verify("backend-timeout")).thenThrow(new BackendAuthException(
                HttpStatus.GATEWAY_TIMEOUT, "AUTH_SERVICE_TIMEOUT", "sensitive upstream detail"
        ));
        when(tokenVerifier.verify("backend-5xx")).thenThrow(new BackendAuthException(
                HttpStatus.BAD_GATEWAY, "AUTH_SERVICE_ERROR", "sensitive upstream detail"
        ));
        when(tokenVerifier.verify("bad-response")).thenReturn(null);

        StompSocket expired = connect("Authorization:Bearer expired-token\n");
        assertErrorAndClose(expired);
        StompSocket unavailable = connect("Authorization:Bearer backend-down\n");
        assertErrorAndClose(unavailable);
        assertTrue(unavailable.frames().stream().noneMatch(frame -> frame.contains("backend-down")));
        assertErrorAndClose(connect("Authorization:Bearer backend-forbidden\n"));
        assertErrorAndClose(connect("Authorization:Bearer backend-timeout\n"));
        assertErrorAndClose(connect("Authorization:Bearer backend-5xx\n"));
        StompSocket badResponse = connect("Authorization:Bearer bad-response\n");
        assertErrorAndClose(badResponse);
        assertTrue(badResponse.frames().stream().anyMatch(frame -> frame.contains("AUTH_SERVICE_UNAVAILABLE")),
                () -> String.join("\n", badResponse.frames()));
    }

    @Test
    void closesTheActualWebSocketWhenAccessTokenExpires() throws Exception {
        when(tokenVerifier.verify("short-lived-token")).thenReturn(new BackendAuthResponse(
                17L, OffsetDateTime.now(ZoneOffset.UTC).plusNanos(TimeUnit.MILLISECONDS.toNanos(1500))
        ));
        StompSocket socket = connect("Authorization:Bearer short-lived-token\n");

        assertTrue(socket.awaitFrame());
        assertTrue(socket.awaitClose());
    }

    @Test
    void rejectsUnapprovedSubscribeAndSendEvenAfterSuccessfulConnect() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        for (String command : List.of(
                "SUBSCRIBE\nid:sub-1\ndestination:/topic/public\n\n\u0000",
                "SEND\ndestination:/app/other\ncontent-type:application/json\n\n{}\u0000"
        )) {
            StompSocket socket = connect("Authorization:Bearer valid-token\n");
            assertTrue(socket.awaitFrame());
            socket.send(command);
            assertTrue(socket.awaitError());
            assertTrue(socket.awaitClose());
        }
    }

    @Test
    void sendsAfterCommitAndRetriesOnlyToSender() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket sender = null;
        StompSocket senderSecondSession = null;
        StompSocket peer = null;
        try {
            sender = connect("Authorization:Bearer sender-token\n");
            peer = connect("Authorization:Bearer peer-token\n");
            assertTrue(sender.awaitFrame());
            assertTrue(peer.awaitFrame());
            subscribe(sender, "sender-messages", "/user/queue/chat-messages", 1);
            subscribe(sender, "sender-errors", "/user/queue/chat-errors", 1);
            subscribe(peer, "peer-messages", "/user/queue/chat-messages", 1);

            UUID clientMessageId = UUID.randomUUID();
            String initialPayload = "{\"clientMessageId\":\"" + clientMessageId
                    + "\",\"content\":\"안녕하세요\"}";
            sender.send(sendFrame(chatRoomId, initialPayload));

            assertTrue(sender.awaitFrameContaining("\"type\":\"MESSAGE\""));
            assertTrue(peer.awaitFrameContaining("\"type\":\"MESSAGE\""));
            assertThat(countFramesContaining(sender.frames(), "\"type\":\"MESSAGE\"")).isEqualTo(1);
            assertThat(countMessagesForRoom(sender.frames(), chatRoomId)).isEqualTo(1);
            String delivered = sender.frames().stream()
                    .filter(frame -> frame.contains(clientMessageId.toString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(delivered).contains("\"type\":\"MESSAGE\",\"chatRoomId\":" + chatRoomId
                    + ",\"chatMessageId\":");
            assertThat(delivered).contains("\"clientMessageId\":\"" + clientMessageId + "\"",
                    "\"userId\":17", "+09:00");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE client_message_id = ?", Long.class,
                    clientMessageId.toString())).isEqualTo(1L);

            senderSecondSession = connect("Authorization:Bearer sender-token\n");
            assertTrue(senderSecondSession.awaitFrame());
            subscribe(senderSecondSession, "sender-second-session", "/user/queue/chat-messages", 2);
            UUID concurrentMessageId = UUID.randomUUID();
            String concurrentFrame = sendFrame(chatRoomId,
                    "{\"clientMessageId\":\"" + concurrentMessageId + "\",\"content\":\"동시 재전송\"}");
            CountDownLatch startTogether = new CountDownLatch(1);
            StompSocket primarySender = sender;
            StompSocket retrySocket = senderSecondSession;
            CompletableFuture<Void> firstSend = CompletableFuture.runAsync(() -> sendAfterGate(primarySender, concurrentFrame,
                    startTogether));
            CompletableFuture<Void> duplicateSend = CompletableFuture.runAsync(() -> sendAfterGate(retrySocket,
                    concurrentFrame, startTogether));
            startTogether.countDown();
            CompletableFuture.allOf(firstSend, duplicateSend).get(5, TimeUnit.SECONDS);
            assertTrue(sender.awaitFrameCountContaining(concurrentMessageId.toString(), 2));
            assertTrue(senderSecondSession.awaitFrameCountContaining(concurrentMessageId.toString(), 2));
            assertTrue(peer.awaitFrameContaining(concurrentMessageId.toString()));
            assertTrue(peer.awaitStableFrameCountContaining(concurrentMessageId.toString(), 1,
                    Duration.ofMillis(300)));
            assertThat(countFramesContaining(peer.frames(), concurrentMessageId.toString())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE client_message_id = ?", Long.class,
                    concurrentMessageId.toString())).isEqualTo(1L);

            UUID tooLongMessageId = UUID.randomUUID();
            sender.send(sendFrame(chatRoomId, "{\"clientMessageId\":\"" + tooLongMessageId
                    + "\",\"content\":\"" + "가".repeat(501) + "\"}"));
            assertTrue(sender.awaitFrameContaining("MESSAGE_TOO_LONG"));
            assertTrue(sender.frames().stream().anyMatch(frame -> frame.contains(tooLongMessageId.toString())));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE client_message_id = ?", Long.class,
                    tooLongMessageId.toString())).isZero();

            sender.send(sendFrame(chatRoomId, initialPayload));
            assertTrue(sender.awaitFrameCountContaining("\"type\":\"MESSAGE\"", 2));
            assertTrue(peer.awaitStableFrameCountContaining(clientMessageId.toString(), 1,
                    Duration.ofMillis(300)));
            assertThat(countFramesContaining(peer.frames(), clientMessageId.toString())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE client_message_id = ?", Long.class,
                    clientMessageId.toString())).isEqualTo(1L);

            sender.send(sendFrame(chatRoomId,
                    "{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\"변경된 내용\"}"));
            assertTrue(sender.awaitFrameContaining("CLIENT_MESSAGE_ID_CONFLICT"));
            assertThat(countFramesContaining(peer.frames(), clientMessageId.toString())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE client_message_id = ?", Long.class,
                    clientMessageId.toString())).isEqualTo(1L);
        } finally {
            closeQuietly(sender);
            closeQuietly(senderSecondSession);
            closeQuietly(peer);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    private long createActiveRoom() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat_rooms (match_id, expires_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
            statement.setTimestamp(2, Timestamp.valueOf(now.plusDays(1)));
            return statement;
        }, keyHolder);
        long chatRoomId = keyHolder.getKey().longValue();
        jdbcTemplate.update("INSERT INTO chat_room_participants "
                        + "(chat_room_id, user_id, nickname, joined_at, left_at) VALUES (?, ?, ?, ?, NULL)",
                chatRoomId, 17L, "sender", Timestamp.valueOf(now));
        jdbcTemplate.update("INSERT INTO chat_room_participants "
                        + "(chat_room_id, user_id, nickname, joined_at, left_at) VALUES (?, ?, ?, ?, NULL)",
                chatRoomId, 28L, "peer", Timestamp.valueOf(now));
        return chatRoomId;
    }

    private void subscribe(StompSocket socket, String id, String destination, int expectedUserSubscriptions)
            throws Exception {
        socket.send("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\n\n\u0000");
        assertTrue(subscriptionEvents.awaitSubscription(
                        socket.userName(), destination, expectedUserSubscriptions, Duration.ofSeconds(5)),
                "STOMP 구독 등록 이벤트를 기다리는 데 실패했습니다.");
    }

    private String sendFrame(long chatRoomId, String payload) {
        return "SEND\ndestination:/app/chat-rooms/" + chatRoomId
                + "/messages\ncontent-type:application/json\n\n" + payload + "\u0000";
    }

    private void sendAfterGate(StompSocket socket, String frame, CountDownLatch gate) {
        try {
            gate.await(5, TimeUnit.SECONDS);
            socket.send(frame);
        } catch (Exception exception) {
            throw new IllegalStateException("테스트 메시지 전송에 실패했습니다.", exception);
        }
    }

    private long countMessagesForRoom(List<String> frames, long chatRoomId) {
        return frames.stream().filter(frame -> frame.contains("\"chatRoomId\":" + chatRoomId)).count();
    }

    private long countFramesContaining(List<String> frames, String value) {
        return frames.stream().filter(frame -> frame.contains(value)).count();
    }

    private void closeQuietly(StompSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 테스트 종료 중 이미 닫힌 연결은 추가 처리를 하지 않습니다.
            }
        }
    }

    @Test
    void rejectsCommandsBeforeConnectAndClosesAfterDisconnectReceipt() throws Exception {
        StompSocket unauthenticated = open();
        unauthenticated.send("SUBSCRIBE\nid:unauthenticated\ndestination:/user/queue/chat-messages\n\n\u0000");
        assertErrorAndClose(unauthenticated);

        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket authenticated = connect("Authorization:Bearer valid-token\n");
        assertTrue(authenticated.awaitFrame());
        authenticated.send("DISCONNECT\nreceipt:disconnect-1\n\n\u0000");
        assertTrue(authenticated.awaitFrameContaining("RECEIPT\nreceipt-id:disconnect-1"));
        assertTrue(authenticated.awaitClose());
        assertTrue(authenticated.frames().stream().noneMatch(frame -> frame.contains("ERROR")));
        List<String> events = authenticated.events();
        int receiptIndex = indexOfContaining(events, "RECEIPT\nreceipt-id:disconnect-1");
        int closeIndex = events.indexOf("CLOSED");
        assertTrue(receiptIndex >= 0 && closeIndex > receiptIndex, () -> String.join("\n", events));
    }

    @Test
    void closesAbnormallyTerminatedWebSocket() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket socket = connect("Authorization:Bearer valid-token\n");
        assertTrue(socket.awaitFrame());

        socket.close();

        assertTrue(socket.listener().closed);
    }

    @Test
    void duplicateDisconnectDoesNotPreventReceiptOrTransportClosure() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket socket = connect("Authorization:Bearer valid-token\n");
        assertTrue(socket.awaitFrame());

        socket.send("DISCONNECT\nreceipt:disconnect-first\n\n\u0000");
        try {
            socket.send("DISCONNECT\nreceipt:disconnect-second\n\n\u0000");
        } catch (Exception ignored) {
            // 첫 종료 완료 후 두 번째 전송이 도착하면 transport가 이미 닫힐 수 있습니다.
        }

        assertTrue(socket.awaitFrameContaining("RECEIPT\nreceipt-id:disconnect-first"));
        assertTrue(socket.awaitClose());
    }

    @Test
    void sameUserCanHoldMultipleIndependentWebSocketSessions() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket first = connect("Authorization:Bearer valid-token\n");
        StompSocket second = connect("Authorization:Bearer valid-token\n");
        assertTrue(first.awaitFrame());
        assertTrue(second.awaitFrame());

        first.close();

        assertTrue(first.listener().closed);
        assertTrue(!second.listener().closed);
        second.close();
    }

    @Test
    void expirationAndDisconnectRaceStillClosesTheTransport() throws Exception {
        when(tokenVerifier.verify("short-lived-token")).thenReturn(new BackendAuthResponse(
                17L, OffsetDateTime.now(ZoneOffset.UTC).plusNanos(TimeUnit.MILLISECONDS.toNanos(1500))
        ));
        StompSocket socket = connect("Authorization:Bearer short-lived-token\n");
        assertTrue(socket.awaitFrame());

        Thread disconnect = new Thread(() -> {
            try {
                Thread.sleep(1400);
                socket.send("DISCONNECT\nreceipt:near-expiry\n\n\u0000");
            } catch (Exception ignored) {
                // 만료 작업이 먼저 연결을 닫으면 DISCONNECT 전송은 실패할 수 있습니다.
            }
        });
        disconnect.start();

        assertTrue(socket.awaitClose());
        disconnect.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!disconnect.isAlive());
    }

    @Test
    void rejectsSecondConnectThatCouldReplaceThePrincipal() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket socket = connect("Authorization:Bearer valid-token\n");
        assertTrue(socket.awaitFrame());
        socket.send("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer other-user-token\n\n\u0000");
        assertErrorAndClose(socket);
        verify(tokenVerifier, org.mockito.Mockito.times(1)).verify("valid-token");
        verify(tokenVerifier, org.mockito.Mockito.never()).verify("other-user-token");
    }

    @Test
    void rejectsUnconfiguredOriginAtHandshake() {
        assertHandshakeRejected("https://untrusted.example");
    }

    @Test
    void rejectsMissingEmptyMalformedAndDuplicateOriginAtHandshake() {
        assertHandshakeRejected();
        assertHandshakeRejected("");
        assertHandshakeRejected("not-an-origin");
        assertHandshakeRejected("http://localhost", "http://localhost");
    }

    private StompSocket connect(String headers) throws Exception {
        StompSocket socket = open();
        socket.send("CONNECT\naccept-version:1.2\nhost:localhost\n" + headers + "\n\u0000");
        return socket;
    }

    private StompSocket open() throws Exception {
        return openWithOrigins("http://localhost");
    }

    private StompSocket openWithOrigins(String... origins) throws Exception {
        RecordingListener listener = new RecordingListener();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("v12.stomp");
        Arrays.stream(origins).forEach(origin -> builder.header("Origin", origin));
        WebSocket socket = builder.buildAsync(endpoint(), listener)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        return new StompSocket(socket, listener);
    }

    private void assertHandshakeRejected(String... origins) {
        assertThrows(CompletionException.class, () -> openWithOrigins(origins));
    }

    private int indexOfContaining(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(expected)) {
                return index;
            }
        }
        return -1;
    }

    private URI endpoint() {
        return URI.create("ws://127.0.0.1:" + port + "/ws");
    }

    private OffsetDateTime futureExpiry() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2);
    }

    private void assertErrorAndClose(StompSocket socket) throws Exception {
        assertTrue(socket.awaitError());
        assertTrue(socket.awaitClose());
        assertTrue(socket.frames().stream().anyMatch(frame -> frame.contains("ERROR")));
    }

    private record StompSocket(WebSocket socket, RecordingListener listener) {

        private boolean awaitFrame() throws InterruptedException {
            return listener.frameLatch.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitError() throws InterruptedException {
            return awaitFrameContaining("ERROR");
        }

        private boolean awaitFrameContaining(String marker) throws InterruptedException {
            return awaitFrameCountContaining(marker, 1);
        }

        private boolean awaitFrameCountContaining(String marker, long expectedCount) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            synchronized (listener.frameMonitor) {
                while (listener.frames.stream().filter(frame -> frame.contains(marker)).count() < expectedCount) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(listener.frameMonitor, remaining);
                }
                return true;
            }
        }

        private boolean awaitStableFrameCountContaining(
                String marker,
                long expectedCount,
                Duration quietPeriod
        ) throws InterruptedException {
            long deadline = System.nanoTime() + quietPeriod.toNanos();
            synchronized (listener.frameMonitor) {
                while (true) {
                    long actualCount = listener.frames.stream().filter(frame -> frame.contains(marker)).count();
                    if (actualCount > expectedCount) {
                        return false;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return actualCount == expectedCount;
                    }
                    TimeUnit.NANOSECONDS.timedWait(listener.frameMonitor, remaining);
                }
            }
        }

        private String userName() {
            return listener.frames.stream()
                    .filter(frame -> frame.startsWith("CONNECTED\n"))
                    .flatMap(frame -> frame.lines())
                    .filter(line -> line.startsWith("user-name:"))
                    .map(line -> line.substring("user-name:".length()).trim())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("CONNECTED 프레임에 사용자 식별자가 없습니다."));
        }

        private boolean awaitClose() throws InterruptedException {
            return listener.closeLatch.await(5, TimeUnit.SECONDS);
        }

        private List<String> frames() {
            return List.copyOf(listener.frames);
        }

        private List<String> events() {
            return List.copyOf(listener.events);
        }

        private void send(String frame) throws Exception {
            socket.sendText(frame, true).get(5, TimeUnit.SECONDS);
        }

        private void close() throws Exception {
            if (!listener.closed) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
                awaitClose();
            }
        }
    }

    private static final class RecordingListener implements WebSocket.Listener {

        private final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        private final CountDownLatch frameLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final Object frameMonitor = new Object();
        private final StringBuilder currentFrame = new StringBuilder();
        private volatile boolean closed;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            currentFrame.append(data);
            if (last) {
                frames.add(currentFrame.toString());
                events.add(currentFrame.toString());
                currentFrame.setLength(0);
                frameLatch.countDown();
                synchronized (frameMonitor) {
                    frameMonitor.notifyAll();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            events.add("CLOSED");
            closeLatch.countDown();
            synchronized (frameMonitor) {
                frameMonitor.notifyAll();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeLatch.countDown();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SubscriptionTestConfiguration {

        @Bean
        SubscriptionEvents subscriptionEvents() {
            return new SubscriptionEvents();
        }
    }

    static class SubscriptionEvents implements ApplicationListener<ApplicationEvent> {

        private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof SessionSubscribeEvent subscribeEvent) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(subscribeEvent.getMessage());
                String sessionId = accessor.getSessionId();
                String destination = accessor.getDestination();
                String userName = subscribeEvent.getUser() == null ? null : subscribeEvent.getUser().getName();
                if (sessionId != null && destination != null && userName != null) {
                    subscriptions.add(new Subscription(sessionId, userName, destination));
                    notifyWaiters();
                }
            } else if (event instanceof SessionDisconnectEvent disconnectEvent) {
                subscriptions.removeIf(subscription -> subscription.sessionId().equals(
                        disconnectEvent.getSessionId()));
                notifyWaiters();
            }
        }

        synchronized boolean awaitSubscription(
                String userName,
                String destination,
                int expectedCount,
                Duration timeout
        )
                throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (subscriptions.stream().filter(subscription -> subscription.userName().equals(userName)
                    && subscription.destination().equals(destination)).count() < expectedCount) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return true;
        }

        synchronized void clear() {
            subscriptions.clear();
        }

        private synchronized void notifyWaiters() {
            notifyAll();
        }

        private record Subscription(String sessionId, String userName, String destination) {
        }
    }
}
