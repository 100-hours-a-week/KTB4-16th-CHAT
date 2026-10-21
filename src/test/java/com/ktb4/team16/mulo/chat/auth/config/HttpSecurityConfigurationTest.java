package com.ktb4.team16.mulo.chat.auth.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import com.ktb4.team16.mulo.chat.dto.response.CreateChatRoomResponse;
import com.ktb4.team16.mulo.chat.service.ChatRoomCreateService;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.service.GroupChatRoomJoinService;
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

    @MockitoBean
    private ChatRoomCreateService chatRoomCreateService;

    @MockitoBean
    private GroupChatRoomJoinService groupChatRoomJoinService;

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
    void protectsInternalChatRoomCreationAndReturnsCreatedOrIdempotentStatus() throws Exception {
        String request = """
                {"matchId": 50, "expiresAt": "2026-10-14T00:00:00+09:00", "participants": [
                  {"userId": 17, "nickname": "jettie"}, {"userId": 28, "nickname": "lulu"}]}
                """;
        mockMvc.perform(post("/internal/chat-rooms").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        when(chatRoomCreateService.create(any())).thenReturn(
                new ChatRoomCreateService.Result(new CreateChatRoomResponse(30L), true));
        mockMvc.perform(post("/internal/chat-rooms")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chatRoomId").value(30));

        when(chatRoomCreateService.create(any())).thenReturn(
                new ChatRoomCreateService.Result(new CreateChatRoomResponse(30L), false));
        mockMvc.perform(post("/internal/chat-rooms")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatRoomId").value(30));
    }

    @Test
    void validatesInternalChatRoomCreationPayloadBeforeCallingService() throws Exception {
        mockMvc.perform(post("/internal/chat-rooms")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content("{\"matchId\":50,\"expiresAt\":\"2026-10-14T00:00:00+09:00\",\"participants\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatRoomCreateService);
    }

    @Test
    void protectsGroupJoinOrCreateWithTheExistingInternalApiKey() throws Exception {
        String request = """
                {"groupDate":"2026-10-21","voteQuestionId":88,"userId":17,
                 "nickname":"jettie","voteChoice":"A"}
                """;
        mockMvc.perform(post("/internal/chat-rooms/group/join-or-create")
                        .contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(groupChatRoomJoinService);

        when(groupChatRoomJoinService.joinOrCreate(any())).thenReturn(new GroupChatRoomJoinService.Result(
                new GroupChatJoinOrCreateResponse(501L, 1, true, 1, 0,
                        OffsetDateTime.parse("2026-10-22T00:00:00+09:00")), true));
        mockMvc.perform(post("/internal/chat-rooms/group/join-or-create")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chatRoomId").value(501))
                .andExpect(jsonPath("$.newlyAssigned").value(true));
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
