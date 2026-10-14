package com.ktb4.team16.mulo.chat.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class HttpSecurityConfiguration {

    @Bean
    SecurityFilterChain httpSecurityFilterChain(
            HttpSecurity http,
            BackendTokenVerifier tokenVerifier,
            BackendAuthProperties properties,
            WebSocketHandshakeRequestMatcher webSocketHandshakeMatcher,
            ApiAuthenticationErrorHandler errorHandler
    ) throws Exception {
        InternalApiKeyAuthenticationFilter internalApiKeyAuthenticationFilter =
                new InternalApiKeyAuthenticationFilter(properties, errorHandler);
        BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter =
                new BearerTokenAuthenticationFilter(tokenVerifier, errorHandler, webSocketHandshakeMatcher);
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/chat-rooms", "/api/chat-rooms/**").hasRole("CHAT_USER")
                        .requestMatchers("/internal", "/internal/**").hasRole("INTERNAL_SERVICE")
                        .anyRequest().denyAll()
                )
                .addFilterBefore(internalApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bearerTokenAuthenticationFilter, InternalApiKeyAuthenticationFilter.class);
        return http.build();
    }
}
