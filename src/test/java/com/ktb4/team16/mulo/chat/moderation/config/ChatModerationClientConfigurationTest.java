package com.ktb4.team16.mulo.chat.moderation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.moderation.client.ChatModerationClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatModerationClientConfigurationTest {

    @Test
    @DisplayName("AI 모더레이션 기본 응답 제한은 5초이고 연결 제한은 1초다")
    void configuresModerationTimeoutDefaults() throws IOException {
        ChatModerationProperties properties = loadModerationProperties(new StandardEnvironment());

        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("AI 모더레이션 응답 제한은 전용 환경변수로 재정의할 수 있다")
    void overridesModerationResponseTimeoutFromEnvironmentProperty() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testOverrides",
                Map.of("CHAT_MODERATION_AI_RESPONSE_TIMEOUT", "7s")));

        ChatModerationProperties properties = loadModerationProperties(environment);

        assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("모더레이션 요청은 HTTP/1.1로 전송한다")
    void sendsModerationRequestUsingHttp11() throws IOException {
        AtomicReference<String> receivedProtocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectMapper objectMapper = new ObjectMapper();
        server.createContext("/api/moderation/check", exchange -> {
            receivedProtocol.set(exchange.getProtocol());
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String requestId = objectMapper.readTree(requestBody).path("requestId").asText();
            byte[] responseBody = ("{\"requestId\":\"" + requestId
                    + "\",\"isToxic\":false,\"confidence\":0.0,\"modelVersion\":\"test\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });
        server.start();

        try {
            ChatModerationProperties properties = new ChatModerationProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-token",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));
            HttpClient httpClient = new ChatModerationClientConfiguration()
                    .chatModerationHttpClient(properties);
            ChatModerationClient client = new ChatModerationClient(httpClient, properties, objectMapper);

            assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(client.isToxic(23L, 17L, "테스트 메시지")).isFalse();
            assertThat(receivedProtocol.get()).isEqualTo("HTTP/1.1");
        } finally {
            server.stop(0);
        }
    }

    private ChatModerationProperties loadModerationProperties(StandardEnvironment environment) throws IOException {
        var propertySources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        propertySources.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment)
                .bind("chat.moderation.ai", Bindable.of(ChatModerationProperties.class))
                .orElseThrow(IllegalStateException::new);
    }
}
