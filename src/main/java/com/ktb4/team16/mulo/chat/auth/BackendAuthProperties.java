package com.ktb4.team16.mulo.chat.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.auth.backend")
public record BackendAuthProperties(
        String url,
        String internalApiKey,
        Duration connectTimeout,
        Duration responseTimeout
) {

    public boolean isConfigured() {
        return url != null && !url.isBlank()
                && internalApiKey != null && !internalApiKey.isBlank();
    }
}
