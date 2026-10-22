package com.ktb4.team16.mulo.chat.auth.config;

import com.ktb4.team16.mulo.chat.auth.security.ApiAuthenticationErrorHandler;
import com.ktb4.team16.mulo.chat.auth.security.BearerTokenAuthenticationFilter;
import com.ktb4.team16.mulo.chat.auth.security.InternalApiKeyAuthenticationFilter;
import com.ktb4.team16.mulo.chat.auth.security.WebSocketHandshakeRequestMatcher;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                        .requestMatchers(webSocketHandshakeMatcher).permitAll()
                        .requestMatchers("/api/chat-rooms", "/api/chat-rooms/**").hasRole("CHAT_USER")
                        .requestMatchers("/internal", "/internal/**").hasRole("INTERNAL_SERVICE")
                        .anyRequest().denyAll()
                )
                .addFilterBefore(internalApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bearerTokenAuthenticationFilter, InternalApiKeyAuthenticationFilter.class);
        return http.build();
    }
}
