package com.ktb4.team16.mulo.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.diagnostics.stomp-send")
public record ChatSendDiagnosticProperties(boolean enabled, String deploymentEnvironment) {
}
