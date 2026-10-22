package com.ktb4.team16.mulo.chat.auth.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb4.team16.mulo.chat.auth.security.ApiAuthenticationErrorHandler;
import com.ktb4.team16.mulo.chat.auth.security.WebSocketHandshakeRequestMatcher;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import com.ktb4.team16.mulo.chat.controller.InternalUserController;
import com.ktb4.team16.mulo.chat.dto.response.UpdateNicknameResponse;
import com.ktb4.team16.mulo.chat.service.ChatParticipantNicknameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the nickname internal endpoint's API-key boundary without loading the JPA context. */
@WebMvcTest(controllers = InternalUserController.class, properties = {
        "chat.auth.backend.internal-api-key=test-internal-key"
})
@EnableConfigurationProperties(BackendAuthProperties.class)
@Import({HttpSecurityConfiguration.class, WebSocketHandshakeRequestMatcher.class,
        ApiAuthenticationErrorHandler.class})
class InternalUserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackendTokenVerifier tokenVerifier;

    @MockitoBean
    private ChatParticipantNicknameService nicknameService;

    @Test
    void rejectsMissingOrIncorrectInternalApiKey() throws Exception {
        String body = "{\"nickname\":\"new-name\"}";

        mockMvc.perform(patch("/internal/users/17/nickname")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/internal/users/17/nickname")
                        .header("X-Internal-Api-Key", "wrong-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(nicknameService, tokenVerifier);
    }

    @Test
    void acceptsNicknameUpdateWithTheExistingInternalApiKey() throws Exception {
        when(nicknameService.updateNickname(17L, "new-name"))
                .thenReturn(new UpdateNicknameResponse(17L, 2));

        mockMvc.perform(patch("/internal/users/17/nickname")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content("{\"nickname\":\"new-name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(17))
                .andExpect(jsonPath("$.updatedParticipantCount").value(2));

        verify(nicknameService).updateNickname(17L, "new-name");
        verifyNoInteractions(tokenVerifier);
    }
}
