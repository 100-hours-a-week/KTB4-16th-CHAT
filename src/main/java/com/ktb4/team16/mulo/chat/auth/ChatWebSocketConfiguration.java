package com.ktb4.team16.mulo.chat.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final String allowedOrigins;
    private final StompConnectAuthenticationInterceptor authenticationInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private TaskScheduler messageBrokerTaskScheduler;

    public ChatWebSocketConfiguration(
            @Value("${chat.websocket.allowed-origins:}") String allowedOrigins,
            StompConnectAuthenticationInterceptor authenticationInterceptor,
            WebSocketSessionRegistry sessionRegistry
    ) {
        this.allowedOrigins = allowedOrigins;
        this.authenticationInterceptor = authenticationInterceptor;
        this.sessionRegistry = sessionRegistry;
    }

    // Spring 메시지 브로커의 스케줄러를 지연 주입해 Heartbeat에 사용합니다.
    @Autowired
    public void setMessageBrokerTaskScheduler(
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler
    ) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty() || origins.stream().anyMatch(origin -> origin.equals("*")
                || origin.contains("*"))) {
            throw new IllegalStateException("CHAT_WS_ALLOWED_ORIGINS must contain explicit origins.");
        }
        var endpoint = registry.addEndpoint("/ws");
        endpoint.addInterceptors(new RequiredAllowedOriginHandshakeInterceptor(origins));
        endpoint.setAllowedOrigins(origins.toArray(String[]::new));
        registry.setErrorHandler(new SafeStompSubProtocolErrorHandler());
    }

    // 기존 메시지 경로를 유지하고 STOMP 연결에 양방향 10초 Heartbeat를 설정합니다.
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(messageBrokerTaskScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticationInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionRegistry.decoratorFactory());
    }
}
