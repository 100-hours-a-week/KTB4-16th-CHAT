package com.ktb4.team16.mulo.chat.auth.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.exception.BackendAuthException;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.event.ChatMessageDeliveryEvent;
import com.ktb4.team16.mulo.chat.event.ChatParticipantLeftDeliveryEvent;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.service.ChatMessageDeliveryListener;
import com.ktb4.team16.mulo.chat.service.ChatMessageEventDeliveryService;
import com.ktb4.team16.mulo.chat.moderation.client.ChatModerationClient;
import com.ktb4.team16.mulo.chat.moderation.service.ChatMessageWordFilter;
import com.ktb4.team16.mulo.chat.service.ChatRoomRestService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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

    @MockitoBean
    private ChatModerationClient moderationClient;

    @MockitoBean
    private ChatMessageWordFilter wordFilter;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubscriptionEvents subscriptionEvents;

    @Autowired
    private LeaveEventExpiryGate leaveEventExpiryGate;

    @Autowired
    private MessageDeliveryGate messageDeliveryGate;

    @Autowired
    private ChatRoomRestService chatRoomRestService;

    @Autowired
    private ChatMessageEventDeliveryService chatMessageEventDeliveryService;

    @Autowired
    private ChatMessageDeliveryListener chatMessageDeliveryListener;

    @Autowired
    private ChatRoomParticipantRepository participantRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearRecordedSubscriptions() {
        when(wordFilter.filter(any())).thenReturn(new ChatMessageWordFilter.Result(false, false));
        when(moderationClient.isToxic(any(), any(), any())).thenReturn(false);
        subscriptionEvents.clear();
        leaveEventExpiryGate.reset();
        messageDeliveryGate.reset();
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

    // 클라이언트가 Heartbeat를 보내지 못해도 서버의 유휴 신호가 도착하는지 검증합니다.
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

    // 인증된 클라이언트의 개행 Heartbeat가 오류나 연결 종료를 만들지 않는지 검증합니다.
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
        when(tokenVerifier.verify("backend-timeout"))
                .thenThrow(new BackendAuthException(ChatErrorCode.AUTH_SERVICE_TIMEOUT));
        when(tokenVerifier.verify("backend-5xx"))
                .thenThrow(new BackendAuthException(ChatErrorCode.AUTH_SERVICE_ERROR));
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
    void rejectsUnapprovedDestinationsAndUnsupportedCommandsAfterSuccessfulConnect() throws Exception {
        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        List<String[]> rejectedCommands = List.of(
                new String[]{"SUBSCRIBE\nid:sub-1\ndestination:/topic/public\n\n\u0000",
                        "STOMP_DESTINATION_FORBIDDEN"},
                new String[]{"SEND\ndestination:/app/other\ncontent-type:application/json\n\n{}\u0000",
                        "STOMP_DESTINATION_FORBIDDEN"},
                new String[]{"ACK\nid:ack-1\n\n\u0000", "COMMAND_NOT_ALLOWED"}
        );
        for (String[] rejectedCommand : rejectedCommands) {
            StompSocket socket = connect("Authorization:Bearer valid-token\n");
            assertTrue(socket.awaitFrame());
            socket.send(rejectedCommand[0]);
            assertTrue(socket.awaitError());
            assertTrue(socket.awaitFrameContaining(rejectedCommand[1]));
            assertTrue(socket.awaitClose());
        }
    }

    @Test
    void sendsAfterCommitAndRetriesOnlyToSender() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        when(tokenVerifier.verify("third-token"))
                .thenReturn(new BackendAuthResponse(99L, futureExpiry()));
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

    @Test
    void committedMessageRemainsStoredWhenOneRecipientDeliveryFails() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        AtomicInteger senderDeliveryFailures = new AtomicInteger();
        StompSocket sender = null;
        StompSocket peer = null;

        doAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).equals("17")) {
                senderDeliveryFailures.incrementAndGet();
                throw new IllegalStateException("테스트 전달 실패");
            }
            return invocation.callRealMethod();
        }).when(messagingTemplate).convertAndSendToUser(
                any(String.class), eq("/queue/chat-messages"), any(ChatMessageResponse.class));

        try {
            sender = connect("Authorization:Bearer sender-token\n");
            peer = connect("Authorization:Bearer peer-token\n");
            assertTrue(sender.awaitFrame());
            assertTrue(peer.awaitFrame());
            subscribe(sender, "sender-message-failure", "/user/queue/chat-messages", 1);
            subscribe(peer, "peer-message-failure", "/user/queue/chat-messages", 1);

            UUID clientMessageId = UUID.randomUUID();
            sender.send(sendFrame(chatRoomId, "{\"clientMessageId\":\"" + clientMessageId
                    + "\",\"content\":\"수신자 전달 실패 검증\"}"));

            assertTrue(peer.awaitFrameContaining(clientMessageId.toString()));
            assertTrue(sender.awaitStableFrameCountContaining(
                    clientMessageId.toString(), 0, Duration.ofMillis(300)));
            assertThat(senderDeliveryFailures).hasValue(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE chat_room_id = ? AND client_message_id = ?",
                    Long.class, chatRoomId, clientMessageId.toString())).isEqualTo(1L);
        } finally {
            closeQuietly(sender);
            closeQuietly(peer);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void suppressesMessageForAnyParticipantWhoLeavesAfterSendCommit() throws Exception {
        assertMessageSuppressedAfterLeave("peer-token", "sender-token", 28L, 17L);
        assertMessageSuppressedAfterLeave("sender-token", "peer-token", 17L, 28L);
    }

    @Test
    void deliversCommittedMessageAfterRoomExpiresBeforeDispatch() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        when(tokenVerifier.verify("third-token"))
                .thenReturn(new BackendAuthResponse(99L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket sender = null;
        StompSocket peer = null;
        StompSocket thirdParty = null;
        messageDeliveryGate.pauseNextEvent();
        try {
            sender = connect("Authorization:Bearer sender-token\n");
            peer = connect("Authorization:Bearer peer-token\n");
            thirdParty = connect("Authorization:Bearer third-token\n");
            assertTrue(sender.awaitFrame());
            assertTrue(peer.awaitFrame());
            assertTrue(thirdParty.awaitFrame());
            subscribe(sender, "sender-messages", "/user/queue/chat-messages", 1);
            subscribe(peer, "peer-messages", "/user/queue/chat-messages", 1);
            subscribe(thirdParty, "third-messages", "/user/queue/chat-messages", 1);

            UUID clientMessageId = UUID.randomUUID();
            sender.send(sendFrame(chatRoomId, "{\"clientMessageId\":\"" + clientMessageId
                    + "\",\"content\":\"커밋 후 만료\"}"));

            assertTrue(messageDeliveryGate.awaitEvent(), "SEND 커밋 후 전달 직전에서 대기해야 합니다.");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE chat_room_id = ? AND client_message_id = ?",
                    Long.class, chatRoomId, clientMessageId.toString())).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM chat_rooms WHERE chat_room_id = ?", Timestamp.class, chatRoomId)
                    .toLocalDateTime()).isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")));

            LocalDateTime expiredAt = LocalDateTime.of(2000, 1, 1, 0, 0);
            jdbcTemplate.update("UPDATE chat_rooms SET expires_at = ? WHERE chat_room_id = ?",
                    Timestamp.valueOf(expiredAt), chatRoomId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM chat_rooms WHERE chat_room_id = ?", Timestamp.class, chatRoomId)
                    .toLocalDateTime()).isEqualTo(expiredAt);

            messageDeliveryGate.releaseEvent();
            assertTrue(sender.awaitFrameContaining(clientMessageId.toString()));
            assertTrue(peer.awaitFrameContaining(clientMessageId.toString()));
            assertTrue(thirdParty.awaitStableFrameCountContaining(
                    clientMessageId.toString(), 0, Duration.ofMillis(300)));
        } finally {
            messageDeliveryGate.releaseEvent();
            closeQuietly(sender);
            closeQuietly(peer);
            closeQuietly(thirdParty);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void eachRecipientUsesAnIndependentTransactionAndTheRowLockCoversDispatch() throws Exception {
        long chatRoomId = createActiveRoom();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch lockAttemptStarted = new CountDownLatch(1);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        AtomicInteger deliveryCalls = new AtomicInteger();
        AtomicInteger completedTransactions = new AtomicInteger();

        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            int callNumber = deliveryCalls.incrementAndGet();
            assertThat(completedTransactions.get()).isEqualTo(callNumber - 1);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completedTransactions.incrementAndGet();
                }
            });
            if (callNumber == 1) {
                dispatchStarted.countDown();
                assertTrue(releaseDispatch.await(10, TimeUnit.SECONDS), "전달 잠금 테스트가 해제되어야 합니다.");
            }
            return invocation.callRealMethod();
        }).when(messagingTemplate).convertAndSendToUser(
                any(String.class), eq("/queue/chat-messages"), any(ChatMessageResponse.class));

        ChatMessageResponse response = new ChatMessageResponse(
                "MESSAGE", chatRoomId, 1001L, UUID.randomUUID(), 17L, "lock test", OffsetDateTime.now(ZoneOffset.UTC));
        CompletableFuture<Void> delivery = CompletableFuture.runAsync(() -> {
            chatMessageEventDeliveryService.deliverIfActive(chatRoomId, 17L, response);
            chatMessageEventDeliveryService.deliverIfActive(chatRoomId, 28L, response);
        });
        CompletableFuture<Void> competingLock = null;
        try {
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS));
            competingLock = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        lockAttemptStarted.countDown();
                        participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(chatRoomId, 17L)
                                .orElseThrow();
                        lockAcquired.countDown();
                    }));
            assertTrue(lockAttemptStarted.await(5, TimeUnit.SECONDS));
            assertFalse(lockAcquired.await(200, TimeUnit.MILLISECONDS),
                    "MESSAGE 발행 중에는 해당 참가자 행 잠금이 유지되어야 합니다.");

            releaseDispatch.countDown();
            delivery.get(5, TimeUnit.SECONDS);
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));
            competingLock.get(5, TimeUnit.SECONDS);
            assertThat(deliveryCalls).hasValue(2);
            assertThat(completedTransactions).hasValue(2);
        } finally {
            releaseDispatch.countDown();
            if (competingLock != null) {
                competingLock.get(5, TimeUnit.SECONDS);
            }
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void leaveTransactionThatLocksFirstPreventsSubsequentMessageDelivery() throws Exception {
        long chatRoomId = createActiveRoom();
        CountDownLatch leaveLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLeaveTransaction = new CountDownLatch(1);
        CountDownLatch deliveryAttemptStarted = new CountDownLatch(1);
        ChatMessageResponse response = new ChatMessageResponse(
                "MESSAGE", chatRoomId, 1003L, UUID.randomUUID(), 17L, "leave lock first", OffsetDateTime.now());

        CompletableFuture<Void> leaveTransaction = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    var recipient = participantRepository.findByChatRoomChatRoomIdAndUserIdForUpdate(chatRoomId, 28L)
                            .orElseThrow();
                    leaveLockAcquired.countDown();
                    try {
                        assertTrue(releaseLeaveTransaction.await(10, TimeUnit.SECONDS),
                                "퇴장 트랜잭션 잠금 테스트가 해제되어야 합니다.");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("퇴장 트랜잭션 잠금 테스트가 중단되었습니다.", exception);
                    }
                    recipient.leave();
                }));
        CompletableFuture<Void> delivery = null;
        try {
            assertTrue(leaveLockAcquired.await(5, TimeUnit.SECONDS));
            delivery = CompletableFuture.runAsync(() -> {
                deliveryAttemptStarted.countDown();
                chatMessageEventDeliveryService.deliverIfActive(chatRoomId, 28L, response);
            });
            assertTrue(deliveryAttemptStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> pendingDelivery = delivery;
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> pendingDelivery.get(200, TimeUnit.MILLISECONDS),
                    "퇴장 트랜잭션이 먼저 획득한 참가자 잠금 동안 MESSAGE 전달이 대기해야 합니다.");

            releaseLeaveTransaction.countDown();
            leaveTransaction.get(5, TimeUnit.SECONDS);
            delivery.get(5, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT left_at FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?",
                    Timestamp.class, chatRoomId, 28L)).isNotNull();
            verify(messagingTemplate, org.mockito.Mockito.never()).convertAndSendToUser(
                    eq("28"), eq("/queue/chat-messages"), eq(response));
        } finally {
            releaseLeaveTransaction.countDown();
            leaveTransaction.get(5, TimeUnit.SECONDS);
            if (delivery != null) {
                delivery.get(5, TimeUnit.SECONDS);
            }
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void concurrentLeaveWaitsForMessageDispatchHoldingTheParticipantLock() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch leaveAttemptStarted = new CountDownLatch(1);

        doAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).equals("17")) {
                dispatchStarted.countDown();
                assertTrue(releaseDispatch.await(10, TimeUnit.SECONDS), "전달 잠금 테스트가 해제되어야 합니다.");
            }
            return invocation.callRealMethod();
        }).when(messagingTemplate).convertAndSendToUser(
                any(String.class), eq("/queue/chat-messages"), any(ChatMessageResponse.class));

        ChatMessageResponse response = new ChatMessageResponse(
                "MESSAGE", chatRoomId, 1002L, UUID.randomUUID(), 17L, "leave race", OffsetDateTime.now(ZoneOffset.UTC));
        CompletableFuture<Void> delivery = CompletableFuture.runAsync(() -> chatMessageDeliveryListener.deliver(
                new ChatMessageDeliveryEvent(response, List.of(17L, 28L))));
        CompletableFuture<HttpResponse<String>> leave = null;
        try {
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS));
            leave = CompletableFuture.supplyAsync(() -> {
                leaveAttemptStarted.countDown();
                try {
                    return leaveRequest(chatRoomId, "sender-token");
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            assertTrue(leaveAttemptStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<HttpResponse<String>> leaveAttempt = leave;
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> leaveAttempt.get(200, TimeUnit.MILLISECONDS),
                    "MESSAGE 전달 중 참가자 잠금을 획득한 퇴장 요청은 대기해야 합니다.");

            releaseDispatch.countDown();
            delivery.get(5, TimeUnit.SECONDS);
            assertThat(leave.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            verify(messagingTemplate).convertAndSendToUser(
                    eq("17"), eq("/queue/chat-messages"), eq(response));
            verify(messagingTemplate).convertAndSendToUser(
                    eq("28"), eq("/queue/chat-messages"), eq(response));
        } finally {
            releaseDispatch.countDown();
            if (leave != null) {
                leave.get(5, TimeUnit.SECONDS);
            }
            delivery.get(5, TimeUnit.SECONDS);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    private void assertMessageSuppressedAfterLeave(
            String leavingToken,
            String remainingToken,
            long leavingUserId,
            long remainingUserId
    ) throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        when(tokenVerifier.verify("third-token"))
                .thenReturn(new BackendAuthResponse(99L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket sender = null;
        StompSocket peer = null;
        StompSocket thirdParty = null;
        messageDeliveryGate.pauseNextEvent();
        try {
            sender = connect("Authorization:Bearer sender-token\n");
            peer = connect("Authorization:Bearer peer-token\n");
            thirdParty = connect("Authorization:Bearer third-token\n");
            assertTrue(sender.awaitFrame());
            assertTrue(peer.awaitFrame());
            assertTrue(thirdParty.awaitFrame());
            subscribe(sender, "sender-messages", "/user/queue/chat-messages", 1);
            subscribe(peer, "peer-messages", "/user/queue/chat-messages", 1);
            subscribe(thirdParty, "third-messages", "/user/queue/chat-messages", 1);

            UUID clientMessageId = UUID.randomUUID();
            sender.send(sendFrame(chatRoomId, "{\"clientMessageId\":\"" + clientMessageId
                    + "\",\"content\":\"퇴장 경합\"}"));
            assertTrue(messageDeliveryGate.awaitEvent(), "SEND 커밋 후 전달 직전에서 대기해야 합니다.");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_messages WHERE chat_room_id = ? AND client_message_id = ?",
                    Long.class, chatRoomId, clientMessageId.toString())).isEqualTo(1L);

            HttpResponse<String> leaveResponse = leaveRequest(chatRoomId, leavingToken);
            assertThat(leaveResponse.statusCode()).isEqualTo(200);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT left_at FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?",
                    Timestamp.class, chatRoomId, leavingUserId)).isNotNull();

            messageDeliveryGate.releaseEvent();
            StompSocket leaver = leavingUserId == 17L ? sender : peer;
            StompSocket remaining = remainingUserId == 17L ? sender : peer;
            assertTrue(remaining.awaitFrameContaining(clientMessageId.toString()));
            assertTrue(leaver.awaitStableFrameCountContaining(
                    clientMessageId.toString(), 0, Duration.ofMillis(300)));
            assertTrue(thirdParty.awaitStableFrameCountContaining(
                    clientMessageId.toString(), 0, Duration.ofMillis(300)));
        } finally {
            messageDeliveryGate.releaseEvent();
            closeQuietly(sender);
            closeQuietly(peer);
            closeQuietly(thirdParty);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void committedRestLeaveNotifiesOnlyTheRemainingActiveParticipant() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        when(tokenVerifier.verify("third-token"))
                .thenReturn(new BackendAuthResponse(99L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket sender = null;
        StompSocket peer = null;
        StompSocket thirdParty = null;
        try {
            sender = connect("Authorization:Bearer sender-token\n");
            peer = connect("Authorization:Bearer peer-token\n");
            thirdParty = connect("Authorization:Bearer third-token\n");
            assertTrue(sender.awaitFrame());
            assertTrue(peer.awaitFrame());
            assertTrue(thirdParty.awaitFrame());
            subscribe(sender, "sender-events", "/user/queue/chat-events", 1);
            subscribe(peer, "peer-events", "/user/queue/chat-events", 1);
            subscribe(thirdParty, "third-party-events", "/user/queue/chat-events", 1);

            HttpResponse<String> response = leaveRequest(chatRoomId, "sender-token");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("채팅방에서 퇴장했습니다.");
            assertTrue(peer.awaitFrameContaining("PARTICIPANT_LEFT"));
            String eventFrame = peer.frames().stream()
                    .filter(frame -> frame.contains("PARTICIPANT_LEFT"))
                    .findFirst()
                    .orElseThrow();
            assertThat(eventFrame).contains("\"type\":\"PARTICIPANT_LEFT\"",
                    "\"chatRoomId\":" + chatRoomId, "\"userId\":17",
                    "\"nickname\":\"sender\"", "+09:00");
            assertTrue(sender.awaitStableFrameCountContaining("PARTICIPANT_LEFT", 0, Duration.ofMillis(300)));
            assertTrue(thirdParty.awaitStableFrameCountContaining(
                    "PARTICIPANT_LEFT", 0, Duration.ofMillis(300)));

            HttpResponse<String> duplicateResponse = leaveRequest(chatRoomId, "sender-token");
            assertThat(duplicateResponse.statusCode()).isEqualTo(409);
            assertTrue(peer.awaitStableFrameCountContaining("PARTICIPANT_LEFT", 1, Duration.ofMillis(300)));
        } finally {
            closeQuietly(sender);
            closeQuietly(peer);
            closeQuietly(thirdParty);
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void deliversCommittedLeaveEventWhenRoomExpiresBeforeDeliveryAndPeerIsStillActive() throws Exception {
        when(tokenVerifier.verify("sender-token"))
                .thenReturn(new BackendAuthResponse(17L, futureExpiry()));
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket peer = null;
        leaveEventExpiryGate.pauseNextEvent();
        CompletableFuture<HttpResponse<String>> leaveResponse = null;
        try {
            peer = connect("Authorization:Bearer peer-token\n");
            assertTrue(peer.awaitFrame());
            subscribe(peer, "peer-events", "/user/queue/chat-events", 1);

            leaveResponse = CompletableFuture.supplyAsync(() -> {
                try {
                    return leaveRequest(chatRoomId, "sender-token");
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });

            assertTrue(leaveEventExpiryGate.awaitEvent(), "퇴장 이벤트가 커밋 후 전달 직전에 대기해야 합니다.");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT left_at FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?",
                    Timestamp.class, chatRoomId, 17L)).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT left_at FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?",
                    Timestamp.class, chatRoomId, 28L)).isNull();
            LocalDateTime expiresAtAtCommit = jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM chat_rooms WHERE chat_room_id = ?", Timestamp.class, chatRoomId)
                    .toLocalDateTime();
            assertThat(expiresAtAtCommit).isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")));

            LocalDateTime expiredAt = LocalDateTime.of(2000, 1, 1, 0, 0);
            jdbcTemplate.update("UPDATE chat_rooms SET expires_at = ? WHERE chat_room_id = ?",
                    Timestamp.valueOf(expiredAt), chatRoomId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT expires_at FROM chat_rooms WHERE chat_room_id = ?", Timestamp.class, chatRoomId)
                    .toLocalDateTime()).isEqualTo(expiredAt);

            leaveEventExpiryGate.releaseEvent();
            HttpResponse<String> response = leaveResponse.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertTrue(peer.awaitFrameContaining("PARTICIPANT_LEFT"));
        } finally {
            leaveEventExpiryGate.releaseEvent();
            closeQuietly(peer);
            if (leaveResponse != null) {
                try {
                    leaveResponse.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 실패한 테스트에서도 커밋 후 이벤트 리스너가 정리될 시간을 확보합니다.
                }
            }
            jdbcTemplate.update("DELETE FROM chat_rooms WHERE chat_room_id = ?", chatRoomId);
        }
    }

    @Test
    void rolledBackRestLeaveDoesNotDeliverParticipantLeftEvent() throws Exception {
        when(tokenVerifier.verify("peer-token"))
                .thenReturn(new BackendAuthResponse(28L, futureExpiry()));
        long chatRoomId = createActiveRoom();
        StompSocket peer = null;
        try {
            peer = connect("Authorization:Bearer peer-token\n");
            assertTrue(peer.awaitFrame());
            subscribe(peer, "peer-events", "/user/queue/chat-events", 1);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                chatRoomRestService.leave(chatRoomId, 17L);
                status.setRollbackOnly();
            });

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT left_at FROM chat_room_participants WHERE chat_room_id = ? AND user_id = ?",
                    Timestamp.class, chatRoomId, 17L)).isNull();
            assertTrue(peer.awaitStableFrameCountContaining("PARTICIPANT_LEFT", 0, Duration.ofMillis(500)));
        } finally {
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

    private HttpResponse<String> leaveRequest(long chatRoomId, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/chat-rooms/" + chatRoomId + "/leave"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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

    // 비정상 종료 세션만 정리되고 같은 사용자의 나머지 세션은 유지되는지 검증합니다.
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

        // 일반 프레임과 구별되는 개행 Heartbeat가 도착할 때까지 기다립니다.
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

        @Bean
        LeaveEventExpiryGate leaveEventExpiryGate() {
            return new LeaveEventExpiryGate();
        }

        @Bean
        MessageDeliveryGate messageDeliveryGate() {
            return new MessageDeliveryGate();
        }
    }

    static class LeaveEventExpiryGate {

        private volatile CountDownLatch eventReached;
        private volatile CountDownLatch releaseEvent;

        void pauseNextEvent() {
            eventReached = new CountDownLatch(1);
            releaseEvent = new CountDownLatch(1);
        }

        boolean awaitEvent() throws InterruptedException {
            return eventReached.await(5, TimeUnit.SECONDS);
        }

        void releaseEvent() {
            CountDownLatch current = releaseEvent;
            if (current != null) {
                current.countDown();
            }
        }

        void reset() {
            releaseEvent();
            eventReached = null;
            releaseEvent = null;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        public void pauseBeforeLeaveEventDelivery(ChatParticipantLeftDeliveryEvent ignored) {
            CountDownLatch reached = eventReached;
            CountDownLatch release = releaseEvent;
            if (reached == null || release == null) {
                return;
            }
            reached.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("퇴장 이벤트 경계 테스트 대기 시간이 초과되었습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("퇴장 이벤트 경계 테스트가 중단되었습니다.", exception);
            }
        }
    }

    static class MessageDeliveryGate {

        private volatile CountDownLatch eventReached;
        private volatile CountDownLatch releaseEvent;

        void pauseNextEvent() {
            eventReached = new CountDownLatch(1);
            releaseEvent = new CountDownLatch(1);
        }

        boolean awaitEvent() throws InterruptedException {
            CountDownLatch reached = eventReached;
            return reached != null && reached.await(5, TimeUnit.SECONDS);
        }

        void releaseEvent() {
            CountDownLatch release = releaseEvent;
            if (release != null) {
                release.countDown();
            }
        }

        void reset() {
            releaseEvent();
            eventReached = null;
            releaseEvent = null;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        public void pauseBeforeMessageDelivery(ChatMessageDeliveryEvent ignored) {
            CountDownLatch reached = eventReached;
            CountDownLatch release = releaseEvent;
            if (reached == null || release == null) {
                return;
            }
            reached.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("메시지 전달 경계 테스트 대기 시간이 초과되었습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("메시지 전달 경계 테스트가 중단되었습니다.", exception);
            }
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
