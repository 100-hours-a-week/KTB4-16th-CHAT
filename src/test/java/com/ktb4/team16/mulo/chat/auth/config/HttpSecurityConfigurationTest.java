package com.ktb4.team16.mulo.chat.auth.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;
import com.ktb4.team16.mulo.chat.auth.service.BackendTokenVerifier;
import com.ktb4.team16.mulo.chat.dto.request.GroupChatJoinOrCreateRequest;
import com.ktb4.team16.mulo.chat.dto.response.GroupChatJoinOrCreateResponse;
import com.ktb4.team16.mulo.chat.service.GroupChatRoomJoinService;
import java.time.OffsetDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    // 제거된 2인 생성 경로가 내부 API 인증 뒤에도 노출되지 않는지 검증한다.
    @Test
    void returnsNotFoundForRemovedDirectChatRoomCreation() throws Exception {
        String request = """
                {"matchId": 50, "expiresAt": "2026-10-14T00:00:00+09:00", "participants": [
                  {"userId": 17, "nickname": "jettie"}, {"userId": 28, "nickname": "lulu"}]}
                """;
        mockMvc.perform(post("/internal/chat-rooms").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/chat-rooms")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isNotFound());
    }

    // 제거된 경로에는 이전 요청 검증도 적용되지 않는지 확인한다.
    @Test
    void returnsNotFoundForRemovedDirectChatRoomCreationWithInvalidPayload() throws Exception {
        mockMvc.perform(post("/internal/chat-rooms")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON)
                        .content("{\"matchId\":50,\"expiresAt\":\"2026-10-14T00:00:00+09:00\",\"participants\":[]}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(groupChatRoomJoinService);
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

    // 버전 생략, null, 0, 양수를 인증된 HTTP 요청에서 바인딩하고 기존 201·200 응답을 보존한다.
    @Test
    void bindsOptionalNicknameVersionsAndPreservesGroupResponses() throws Exception {
        var expiresAt = OffsetDateTime.parse("2026-10-22T00:00:00+09:00");
        when(groupChatRoomJoinService.joinOrCreate(any())).thenReturn(
                new GroupChatRoomJoinService.Result(
                        new GroupChatJoinOrCreateResponse(501L, 1, true, 1, 0, expiresAt), true),
                new GroupChatRoomJoinService.Result(
                        new GroupChatJoinOrCreateResponse(501L, 1, false, 1, 0, expiresAt), false),
                new GroupChatRoomJoinService.Result(
                        new GroupChatJoinOrCreateResponse(501L, 1, false, 1, 0, expiresAt), false),
                new GroupChatRoomJoinService.Result(
                        new GroupChatJoinOrCreateResponse(501L, 1, false, 1, 0, expiresAt), false));

        String[] versionFields = {"", ",\"nicknameVersion\":null", ",\"nicknameVersion\":0", ",\"nicknameVersion\":8"};
        for (int index = 0; index < versionFields.length; index++) {
            String body = "{\"groupDate\":\"2026-10-21\",\"voteQuestionId\":88,\"userId\":17,"
                    + "\"nickname\":\"jettie\",\"voteChoice\":\"A\"" + versionFields[index] + "}";
            mockMvc.perform(post("/internal/chat-rooms/group/join-or-create")
                            .header("X-Internal-Api-Key", "test-internal-key")
                            .contentType(APPLICATION_JSON).content(body))
                    .andExpect(index == 0 ? status().isCreated() : status().isOk())
                    .andExpect(jsonPath("$.chatRoomId").value(501))
                    .andExpect(jsonPath("$.roomSequence").value(1))
                    .andExpect(jsonPath("$.newlyAssigned").value(index == 0))
                    .andExpect(jsonPath("$.admittedParticipantCount").value(1))
                    .andExpect(jsonPath("$.activeParticipantCount").value(0))
                    .andExpect(jsonPath("$.expiresAt").value("2026-10-22T00:00:00+09:00"));
        }
        var captor = ArgumentCaptor.forClass(GroupChatJoinOrCreateRequest.class);
        verify(groupChatRoomJoinService, times(4)).joinOrCreate(captor.capture());
        assertThat(captor.getAllValues().stream().map(GroupChatJoinOrCreateRequest::nicknameVersion).toList())
                .containsExactlyElementsOf(Arrays.asList(null, null, 0L, 8L));
    }

    // 음수 버전은 유효성 검사에서 거절하고 그룹 서비스로 전달하지 않는다.
    @Test
    void rejectsNegativeNicknameVersionBeforeCallingGroupService() throws Exception {
        String body = """
                {"groupDate":"2026-10-21","voteQuestionId":88,"userId":17,
                 "nickname":"jettie","voteChoice":"A","nicknameVersion":-1}
                """;
        mockMvc.perform(post("/internal/chat-rooms/group/join-or-create")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(groupChatRoomJoinService);
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
