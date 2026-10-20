package com.ktb4.team16.mulo.chat.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb4.team16.mulo.chat.auth.config.BackendAuthClientConfiguration;
import com.ktb4.team16.mulo.chat.auth.config.HttpSecurityConfiguration;
import com.ktb4.team16.mulo.chat.auth.security.ApiAuthenticationErrorHandler;
import com.ktb4.team16.mulo.chat.auth.security.WebSocketHandshakeRequestMatcher;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HealthController.class, properties = {
        "chat.auth.backend.url=http://backend.test",
        "chat.auth.backend.internal-api-key=test-internal-key"
})
@Import({HttpSecurityConfiguration.class, BackendAuthClientConfiguration.class,
        WebSocketHandshakeRequestMatcher.class, ApiAuthenticationErrorHandler.class})
class HealthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @Test
    void permitsOnlyGetHealthAndKeepsOtherPathsProtected() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/health"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/chat-rooms/17/messages"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/future-endpoint"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokenVerifier);
    }
}
