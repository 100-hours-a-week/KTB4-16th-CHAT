package com.ktb4.team16.mulo.chat.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.auth.backend.url=http://backend.test",
        "chat.auth.backend.internal-api-key=test-internal-key",
        "chat.websocket.allowed-origins=http://localhost"
})
class StompConnectAuthenticationIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

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

    // CONNECTED 프레임에서 서버가 양방향 10초 Heartbeat를 제안하는지 검증합니다.
    @Test
    void negotiatesTenSecondHeartbeatsInBothDirections() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));

        StompSocket socket = connect("Authorization:Bearer valid-token\nheart-beat:10000,10000\n");

        assertTrue(socket.awaitFrame());
        assertTrue(socket.frames().stream().anyMatch(frame -> frame.contains("CONNECTED")
                && frame.contains("heart-beat:10000,10000")),
                () -> String.join("\n", socket.frames()));
        socket.close();
    }

    // 클라이언트가 Heartbeat를 보내지 못해도 서버의 유휴 신호가 실제로 도착하는지 검증합니다.
    @Test
    void sendsServerHeartbeatWhenConnectionIsIdle() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));

        StompSocket socket = connect("Authorization:Bearer valid-token\nheart-beat:0,10000\n");

        assertTrue(socket.awaitFrame());
        assertTrue(socket.awaitHeartbeat(), () -> socket.frames().toString());
        socket.close();
    }

    // 협상한 클라이언트 Heartbeat가 멈추면 서버가 유휴 연결을 정리하는지 검증합니다.
    @Test
    void closesConnectionWhenClientHeartbeatStops() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));

        StompSocket socket = connect("Authorization:Bearer valid-token\nheart-beat:10000,0\n");

        assertTrue(socket.awaitFrame());
        assertTrue(socket.listener().closeLatch.await(45, TimeUnit.SECONDS),
                "클라이언트 Heartbeat가 중단된 연결이 정리되어야 합니다.");
        assertTrue(awaitSessionCount("17", 0));
    }

    // 인증된 클라이언트의 개행 Heartbeat가 오류 프레임이나 연결 종료를 만들지 않는지 검증합니다.
    @Test
    void acceptsClientHeartbeatAfterConnect() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));

        StompSocket socket = connect("Authorization:Bearer valid-token\nheart-beat:10000,10000\n");

        assertTrue(socket.awaitFrame());
        socket.send("\n");
        assertTrue(!socket.listener().closeLatch.await(1, TimeUnit.SECONDS),
                () -> socket.frames().toString());
        socket.close();
    }

    // CONNECT 이전에는 Heartbeat도 인증된 활동으로 취급하지 않는지 검증합니다.
    @Test
    void rejectsClientHeartbeatBeforeConnect() throws Exception {
        StompSocket socket = open();

        socket.send("\n");

        assertErrorAndClose(socket);
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
    void rejectsSubscribeAndSendEvenAfterSuccessfulConnect() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        for (String command : List.of(
                "SUBSCRIBE\nid:sub-1\ndestination:/user/queue/chat-messages\n\n\u0000",
                "SEND\ndestination:/app/chat-rooms/1/messages\ncontent-type:application/json\n\n{}\u0000"
        )) {
            StompSocket socket = connect("Authorization:Bearer valid-token\n");
            assertTrue(socket.awaitFrame());
            socket.send(command);
            assertTrue(socket.awaitError());
            assertTrue(socket.awaitClose());
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

    // STOMP DISCONNECT 없이 연결이 끊겨도 Spring 사용자 세션이 정리되고 새 연결이 인증되는지 검증합니다.
    @Test
    void abruptTransportFailureRemovesOnlyTheFailedSession() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        StompSocket first = connect("Authorization:Bearer valid-token\n");
        StompSocket second = connect("Authorization:Bearer valid-token\n");
        assertTrue(first.awaitFrame());
        assertTrue(second.awaitFrame());
        assertTrue(awaitSessionCount("17", 2));

        first.socket().abort();

        assertTrue(awaitSessionCount("17", 1));
        assertTrue(!second.listener().closed);
        StompSocket reconnected = connect("Authorization:Bearer valid-token\n");
        assertTrue(reconnected.awaitFrame());
        assertTrue(awaitSessionCount("17", 2));
        verify(tokenVerifier, org.mockito.Mockito.times(3)).verify("valid-token");
        reconnected.close();
        second.close();
        assertTrue(awaitSessionCount("17", 0));
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

    // Spring 사용자 레지스트리에 반영된 세션 수를 제한 시간 안에 확인합니다.
    private boolean awaitSessionCount(String userName, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            SimpUser user = simpUserRegistry.getUser(userName);
            int actual = user == null ? 0 : user.getSessions().size();
            if (actual == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private record StompSocket(WebSocket socket, RecordingListener listener) {

        private boolean awaitFrame() throws InterruptedException {
            return listener.frameLatch.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitError() throws InterruptedException {
            return awaitFrameContaining("ERROR");
        }

        private boolean awaitFrameContaining(String marker) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (listener.frames.stream().anyMatch(frame -> frame.contains(marker))) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        private boolean awaitClose() throws InterruptedException {
            return listener.closeLatch.await(5, TimeUnit.SECONDS);
        }

        // 일반 STOMP 프레임과 구별되는 개행 Heartbeat가 도착할 때까지 기다립니다.
        private boolean awaitHeartbeat() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                if (listener.frames.stream().anyMatch(frame -> frame.equals("\n") || frame.equals("\r\n"))) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
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
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            events.add("CLOSED");
            closeLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeLatch.countDown();
        }
    }
}
