package com.ktb4.team16.mulo.chat.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(ChatSendDiagnosticProperties.class)
public class ChatSendDiagnosticConfiguration {

    private static final String DEVELOPMENT_ENVIRONMENT = "development";
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    @Bean
    ChatSendDiagnosticAvailability chatSendDiagnosticAvailability(
            ChatSendDiagnosticProperties properties,
            Environment environment
    ) {
        boolean productionProfileActive = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(PRODUCTION_PROFILES::contains);
        boolean enabled = properties.enabled()
                && DEVELOPMENT_ENVIRONMENT.equals(properties.deploymentEnvironment())
                && !productionProfileActive;
        return new ChatSendDiagnosticAvailability(enabled);
    }
}
