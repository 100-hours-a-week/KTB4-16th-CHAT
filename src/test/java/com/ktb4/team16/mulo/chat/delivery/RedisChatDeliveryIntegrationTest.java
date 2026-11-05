package com.ktb4.team16.mulo.chat.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ktb4.team16.mulo.chat.dto.response.ChatMessageResponse;
import com.ktb4.team16.mulo.chat.service.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.user.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class RedisChatDeliveryIntegrationTest {
    @Test
    void fansOutToBothLocalOwnersWithoutRepublishingAndDrains() throws Exception {
        var codec = new ChatDeliveryCodec(JsonMapper.builder().findAndAddModules().build());
        var properties =
                new ChatRedisProperties(
                        "127.0.0.1",
                        Integer.parseInt(
                                System.getenv().getOrDefault("CHAT_TEST_REDIS_PORT", "16401")),
                        false,
                        null,
                        null,
                        "test-" + UUID.randomUUID(),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(500));
        var delivered = new CountDownLatch(2);
        var closed = new AtomicInteger();
        var states = new ArrayList<ChatDeliveryLifecycle>();
        var transports = new ArrayList<RedisChatDeliveryTransport>();
        try {
            for (int i = 0; i < 2; i++) {
                var users = mock(SimpUserRegistry.class);
                when(users.getUser("17")).thenReturn(mock(SimpUser.class));
                var messages = mock(ChatMessageEventDeliveryService.class);
                doAnswer(
                                call -> {
                                    assertThat((Long) call.getArgument(1)).isEqualTo(17L);
                                    assertThat(
                                                    ((ChatMessageResponse) call.getArgument(2))
                                                            .content())
                                            .isEqualTo("cross-task");
                                    delivered.countDown();
                                    return null;
                                })
                        .when(messages)
                        .deliverIfActive(anyLong(), anyLong(), any());
                var state = new ChatDeliveryLifecycle(closed::incrementAndGet);
                states.add(state);
                var meters = new SimpleMeterRegistry();
                var dispatcher =
                        new ChatLocalDeliveryDispatcher(
                                users,
                                messages,
                                mock(ChatRoomEventDeliveryService.class),
                                codec,
                                state,
                                meters);
                var transport =
                        new RedisChatDeliveryTransport(
                                properties, codec, dispatcher, state, meters);
                transports.add(transport);
                transport.start();
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (states.stream().anyMatch(s -> !s.isReady()) && System.nanoTime() < deadline)
                Thread.sleep(50);
            assertThat(states).allMatch(ChatDeliveryLifecycle::isReady);
            transports
                    .getFirst()
                    .publish(
                            new ChatMessageResponse(
                                    "MESSAGE",
                                    1L,
                                    2L,
                                    UUID.randomUUID(),
                                    17L,
                                    "cross-task",
                                    OffsetDateTime.now(),
                                    null),
                            List.of(17L, 99L));
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(states).allMatch(ChatDeliveryLifecycle::isReady);
        } finally {
            transports.forEach(RedisChatDeliveryTransport::stop);
        }
        assertThat(states).allMatch(ChatDeliveryLifecycle::isDraining);
        assertThat(closed.get()).isEqualTo(2);
    }
}
