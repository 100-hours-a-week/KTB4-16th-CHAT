package com.ktb4.team16.mulo.chat.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ktb4.team16.mulo.chat.auth.config.BackendAuthProperties;
import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.exception.BackendAuthException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class BackendAuthClientTest {

    private static final String ACCESS_TOKEN = "test-access-token";
    private static final String INTERNAL_API_KEY = "test-internal-api-key";

    private MockRestServiceServer server;
    private BackendAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BackendAuthClient(
                builder.build(),
                new BackendAuthProperties("https://backend.test/", INTERNAL_API_KEY,
                        Duration.ofSeconds(1), Duration.ofSeconds(2)),
                Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void sendsBearerTokenAndInternalKeyAndReturnsVerifiedUser() {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(header("X-Internal-Api-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(
                        "{\"userId\":17,\"expiresAt\":\"2026-10-13T19:00:00+09:00\"}",
                        MediaType.APPLICATION_JSON
                ));

        BackendAuthResponse response = client.verify(ACCESS_TOKEN);

        assertThat(response.userId()).isEqualTo(17L);
        assertThat(response.expiresAt().toInstant()).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
        server.verify();
    }

    @Test
    void rejectsExpiredBackendToken() {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withSuccess(
                        "{\"userId\":17,\"expiresAt\":\"2026-10-13T08:59:59Z\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mapsBackendUnauthorizedAndForbiddenResponses() {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        server.reset();
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void mapsBackendOutageAndTimeoutToUnavailable() {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        server.reset();
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withException(new SocketTimeoutException("request timed out")));
        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsBackendConnectionFailureToUnavailable() {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withException(new IOException("connection failed")));
        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void doesNotExposeTokenOrApiKeyInLogsOrFailureResponse(CapturedOutput output) {
        server.expect(requestTo("https://backend.test/internal/auth/verify"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .hasMessageNotContaining(ACCESS_TOKEN)
                .hasMessageNotContaining(INTERNAL_API_KEY);
        assertThat(output.getAll())
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(INTERNAL_API_KEY);
    }

    @Test
    void failsClosedWhenBackendConfigurationIsMissing() {
        BackendAuthClient unconfiguredClient = new BackendAuthClient(
                RestClient.builder().build(),
                new BackendAuthProperties("", "", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> unconfiguredClient.verify(ACCESS_TOKEN))
                .isInstanceOf(BackendAuthException.class)
                .extracting(exception -> ((BackendAuthException) exception).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
