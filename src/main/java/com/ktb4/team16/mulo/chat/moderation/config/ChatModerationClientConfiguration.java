package com.ktb4.team16.mulo.chat.moderation.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatModerationProperties.class)
public class ChatModerationClientConfiguration {

    @Bean("chatModerationHttpClient")
    HttpClient chatModerationHttpClient(ChatModerationProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
