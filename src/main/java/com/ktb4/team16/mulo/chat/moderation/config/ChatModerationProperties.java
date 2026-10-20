package com.ktb4.team16.mulo.chat.moderation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.moderation.ai")
public record ChatModerationProperties(
        String baseUrl,
        String internalToken,
        Duration connectTimeout,
        Duration responseTimeout
) {

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && internalToken != null && !internalToken.isBlank();
    }
}
