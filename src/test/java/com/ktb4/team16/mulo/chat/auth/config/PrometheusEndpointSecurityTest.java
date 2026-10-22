package com.ktb4.team16.mulo.chat.auth.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.security.ApiAuthenticationErrorHandler;
import com.ktb4.team16.mulo.chat.auth.security.WebSocketHandshakeRequestMatcher;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = PrometheusEndpointSecurityTest.PrometheusProbeController.class, properties = {
        "chat.auth.backend.url=http://backend.test",
        "chat.auth.backend.internal-api-key=test-internal-key"
})
@Import({HttpSecurityConfiguration.class, BackendAuthClientConfiguration.class,
        WebSocketHandshakeRequestMatcher.class, ApiAuthenticationErrorHandler.class,
        PrometheusEndpointSecurityTest.PrometheusProbeController.class})
class PrometheusEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @Test
    void permitsOnlyPrometheusGetAndKeepsOtherActuatorAndProtectedPathsDenied() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("# TYPE chat_test_counter counter")));

        mockMvc.perform(post("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/future-endpoint"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/chat-rooms/17/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void retainsInternalApiKeyAndBearerAuthenticationForProtectedRoutes() throws Exception {
        mockMvc.perform(get("/internal/future-endpoint")
                        .header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(status().isNotFound());

        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, OffsetDateTime.parse("2026-10-22T00:00:00+09:00")));
        mockMvc.perform(get("/api/chat-rooms/17/messages")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
        verify(tokenVerifier).verify("valid-token");
    }

    // A probe keeps the MVC security test independent of the application's database-backed context.
    @RestController
    static class PrometheusProbeController {

        @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
        String scrape() {
            return "# HELP chat_test_counter Test scrape metric\n"
                    + "# TYPE chat_test_counter counter\n"
                    + "chat_test_counter 1.0\n";
        }
    }
}
