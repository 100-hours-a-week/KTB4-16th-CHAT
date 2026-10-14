package com.ktb4.team16.mulo.chat.auth;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "chat.auth.backend.url=http://backend.test",
        "chat.auth.backend.internal-api-key=test-internal-key",
        "chat.websocket.allowed-origins=http://localhost"
})
@AutoConfigureMockMvc
class HttpSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @Test
    void requiresBackendVerifiedBearerForChatRoomApi() throws Exception {
        mockMvc.perform(get("/api/chat-rooms/17/messages"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokenVerifier);

        when(tokenVerifier.verify("valid-token"))
                .thenReturn(new BackendAuthResponse(17L, OffsetDateTime.parse("2026-10-14T00:00:00+09:00")));
        mockMvc.perform(get("/api/chat-rooms/17/messages").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
        verify(tokenVerifier).verify("valid-token");
    }

    @Test
    void requiresInternalApiKeyAndDeniesOtherPublicPaths() throws Exception {
        mockMvc.perform(get("/internal/future-endpoint"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/future-endpoint").header("X-Internal-Api-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/future-endpoint").header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/public"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permitsOnlyTheWebSocketHandshakePathForStompAuthentication() throws Exception {
        mockMvc.perform(get("/ws")
                        .header("Upgrade", "websocket")
                .header("Connection", "Upgrade"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
                        401, result.getResponse().getStatus()
                ));
        mockMvc.perform(get("/other")
                        .header("Upgrade", "websocket")
                        .header("Connection", "Upgrade"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokenVerifier);
    }
}
