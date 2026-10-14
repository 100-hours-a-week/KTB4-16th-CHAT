package com.ktb4.team16.mulo.chat.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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

    public ChatWebSocketConfiguration(
            @Value("${chat.websocket.allowed-origins:}") String allowedOrigins,
            StompConnectAuthenticationInterceptor authenticationInterceptor,
            WebSocketSessionRegistry sessionRegistry
    ) {
        this.allowedOrigins = allowedOrigins;
        this.authenticationInterceptor = authenticationInterceptor;
        this.sessionRegistry = sessionRegistry;
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

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue");
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
