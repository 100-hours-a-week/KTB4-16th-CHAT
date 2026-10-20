package com.ktb4.team16.mulo.chat.moderation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ktb4.team16.mulo.chat.moderation.config.ChatModerationProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChatModerationClientTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private AtomicReference<String> suppliedToken;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("요청 ID와 문자열 식별자를 전달하고 AI의 isToxic 판정을 사용한다")
    void sendsContractPayloadAndReturnsToxicFlag() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        suppliedToken = new AtomicReference<>();
        startServer(200, requestBody, false, false, false);

        boolean toxic = client(server.getAddress().getPort()).isToxic(23L, 17L, "원문 메시지");

        assertThat(toxic).isFalse();
        JsonNode request = new ObjectMapper().readTree(requestBody.get());
        assertThat(request.path("requestId").asText()).isNotBlank();
        assertThat(request.path("roomId").asText()).isEqualTo("23");
        assertThat(request.path("userId").asText()).isEqualTo("17");
        assertThat(request.path("text").asText()).isEqualTo("원문 메시지");
        assertThat(suppliedToken.get()).isEqualTo("test-ai-internal-token");
    }

    @Test
    @DisplayName("HTTP 503 응답은 fail-safe 예외로 처리한다")
    void failsClosedForServiceUnavailable() throws IOException {
        startServer(503, new AtomicReference<>(), false, false, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    @Test
    @DisplayName("AI의 400 응답은 유해 판정으로 취급하지 않고 전송을 차단한다")
    void failsClosedForInvalidInputResponse() throws IOException {
        startServer(400, new AtomicReference<>(), false, false, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    @Test
    @DisplayName("requestId가 다르거나 응답 필드가 누락되면 fail-safe 처리한다")
    void failsClosedForInvalidResponse() throws IOException {
        startServer(200, new AtomicReference<>(), true, false, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    @Test
    @DisplayName("1초 응답 제한을 넘는 AI 응답은 fail-safe 처리한다")
    void failsClosedOnResponseTimeout() throws IOException {
        startServer(200, new AtomicReference<>(), false, true, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    @Test
    @DisplayName("유해 AI 판정은 isToxic 값으로 반환한다")
    void returnsToxicFlagWithoutRecomputingConfidence() throws IOException {
        startServer(200, new AtomicReference<>(), false, false, true);

        assertThat(client(server.getAddress().getPort()).isToxic(23L, 17L, "원문" )).isTrue();
    }

    @Test
    @DisplayName("503 응답을 재시도하지 않는다")
    void doesNotRetryModerationRequest() throws IOException {
        startServer(503, new AtomicReference<>(), false, false, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AI 주소 설정이 없으면 요청을 보내지 않고 fail-safe 처리한다")
    void failsClosedWhenBaseUrlIsMissing() {
        ChatModerationProperties properties = new ChatModerationProperties(
                "", "test-ai-internal-token", Duration.ofSeconds(1), Duration.ofSeconds(1));
        ChatModerationClient client = new ChatModerationClient(HttpClient.newHttpClient(), properties,
                new ObjectMapper());

        assertThatThrownBy(() -> client.isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    @Test
    @DisplayName("AI 인증 토큰이 없으면 인증 없는 요청을 보내지 않고 차단한다")
    void failsClosedWhenInternalTokenIsMissing() throws IOException {
        startServer(200, new AtomicReference<>(), false, false, false);
        ChatModerationClient client = new ChatModerationClient(HttpClient.newHttpClient(),
                new ChatModerationProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(), " ",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ObjectMapper());

        assertThatThrownBy(() -> client.isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
        assertThat(requestCount.get()).isZero();
    }

    @Test
    @DisplayName("AI 인증 거부는 fail-safe 차단으로 처리한다")
    void failsClosedWhenAiAuthenticationIsRejected() throws IOException {
        startServer(401, new AtomicReference<>(), false, false, false);

        assertThatThrownBy(() -> client(server.getAddress().getPort()).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("AI 서버 연결 실패는 fail-safe 처리한다")
    void failsClosedWhenConnectionFails() {
        assertThatThrownBy(() -> client(1).isToxic(23L, 17L, "본문"))
                .isInstanceOf(ChatModerationUnavailableException.class);
    }

    private ChatModerationClient client(int port) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return new ChatModerationClient(httpClient,
                new ChatModerationProperties(
                        "http://127.0.0.1:" + port, "test-ai-internal-token",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ObjectMapper());
    }

    private void startServer(int status, AtomicReference<String> requestBody,
            boolean mismatchedId, boolean delay, boolean toxic) throws IOException {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/api/moderation/check", exchange -> {
            requestCount.incrementAndGet();
            if (suppliedToken != null) {
                suppliedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            }
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (delay) {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            String requestId = "invalid";
            if (!mismatchedId && requestBody.get() != null) {
                requestId = new ObjectMapper().readTree(requestBody.get()).path("requestId").asText();
            }
            String body = mismatchedId
                    ? "{\"requestId\":\"different\",\"isToxic\":false}"
                    : "{\"requestId\":\"" + requestId
                    + "\",\"isToxic\":" + toxic + ",\"confidence\":0.99,\"category\":null,"
                    + "\"modelVersion\":\"test-model\"}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 클라이언트 타임아웃 후 연결이 닫히는 상황이다.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
