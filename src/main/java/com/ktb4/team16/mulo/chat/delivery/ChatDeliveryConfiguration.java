package com.ktb4.team16.mulo.chat.delivery;

import com.ktb4.team16.mulo.chat.auth.session.WebSocketSessionRegistry;
import com.ktb4.team16.mulo.chat.service.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.CloseStatus;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class ChatDeliveryConfiguration {
    /** ECS에서 실수로 로컬 전송 모드로 기동하지 못하게 한다. */
    public ChatDeliveryConfiguration(
            @Value("${chat.delivery.enabled:false}") boolean enabled,
            @Value("${AWS_EXECUTION_ENV:}") String execution) {
        if (execution.startsWith("AWS_ECS") && !enabled)
            throw new IllegalArgumentException("ECS requires CHAT_DELIVERY_ENABLED=true");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "chat.delivery", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties(ChatRedisProperties.class)
    static class Enabled {
        /** 연결 객체는 태스크 로컬에 두고 장애 때만 복구용으로 종료한다. */
        @Bean
        ChatDeliveryLifecycle chatDeliveryLifecycle(WebSocketSessionRegistry sessions) {
            return new ChatDeliveryLifecycle(
                    () ->
                            Thread.ofVirtual()
                                    .name("chat-close-for-recovery")
                                    .start(
                                            () ->
                                                    sessions.closeAllForRecovery(
                                                            new CloseStatus(
                                                                    1012,
                                                                    "Chat service recovery"))));
        }

        /** 웹 서버 graceful 정리보다 먼저 새로운 작업을 차단한다. */
        @Bean
        org.springframework.context.ApplicationListener<
                        org.springframework.context.event.ContextClosedEvent>
                chatDrainOnContextClose(ChatDeliveryLifecycle lifecycle) {
            return event -> lifecycle.beginDrain();
        }

        @Bean
        ChatDeliveryCodec chatDeliveryCodec(JsonMapper mapper) {
            return new ChatDeliveryCodec(mapper);
        }

        @Bean
        ChatLocalDeliveryDispatcher chatLocalDeliveryDispatcher(
                SimpUserRegistry users,
                ChatMessageEventDeliveryService messages,
                ChatRoomEventDeliveryService rooms,
                ChatDeliveryCodec codec,
                ChatDeliveryLifecycle lifecycle,
                MeterRegistry meters) {
            return new ChatLocalDeliveryDispatcher(
                    users, messages, rooms, codec, lifecycle, meters);
        }

        @Bean
        RedisChatDeliveryTransport redisChatDeliveryTransport(
                ChatRedisProperties properties,
                ChatDeliveryCodec codec,
                ChatLocalDeliveryDispatcher dispatcher,
                ChatDeliveryLifecycle lifecycle,
                MeterRegistry meters) {
            return new RedisChatDeliveryTransport(properties, codec, dispatcher, lifecycle, meters);
        }
    }
}
