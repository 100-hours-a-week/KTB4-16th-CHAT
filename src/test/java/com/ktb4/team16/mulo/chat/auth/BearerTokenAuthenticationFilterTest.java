package com.ktb4.team16.mulo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class BearerTokenAuthenticationFilterTest {

    private BackendTokenVerifier tokenVerifier;
    private BearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenVerifier = mock(BackendTokenVerifier.class);
        filter = new BearerTokenAuthenticationFilter(
                tokenVerifier,
                new ApiAuthenticationErrorHandler(new ObjectMapper()),
                new WebSocketHandshakeRequestMatcher()
        );
        SecurityContextHolder.clearContext();
    }

    @Test
    void putsVerifiedUserIdInPrincipalWithoutRetainingTokenAsCredentials() throws Exception {
        when(tokenVerifier.verify("opaque-token"))
                .thenReturn(new BackendAuthResponse(17L, OffsetDateTime.parse("2026-10-13T19:00:00+09:00")));
        MockHttpServletRequest request = chatRoomRequest();
        request.addHeader("Authorization", "Bearer opaque-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> observedAuthentication = new AtomicReference<>();
        FilterChain chain = (req, res) -> observedAuthentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observedAuthentication.get().getPrincipal()).isEqualTo(new ChatPrincipal(17L));
        assertThat(observedAuthentication.get().getCredentials()).isNull();
        assertThat(observedAuthentication.get().getAuthorities()).extracting("authority")
                .containsExactly("ROLE_CHAT_USER");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenVerifier).verify("opaque-token");
    }

    @Test
    void rejectsMissingBearerTokenWithoutCallingBackend() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chatRoomRequest(), response, (req, res) -> {
            throw new AssertionError("Unauthenticated request reached application");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void rejectsBackendAuthenticationFailuresWithoutCredentialDetails() throws Exception {
        when(tokenVerifier.verify("forged-token")).thenThrow(BackendAuthException.unauthorized());
        MockHttpServletRequest request = chatRoomRequest();
        request.addHeader("Authorization", "Bearer forged-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("Invalid token reached application");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("forged-token");
    }

    @Test
    void leavesWebSocketHandshakeForTheSeparateHandshakePolicy() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setServletPath("/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("Connection", "keep-alive, Upgrade");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> reachedChain = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> reachedChain.set(true));

        assertThat(reachedChain.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        org.mockito.Mockito.verifyNoInteractions(tokenVerifier);
    }

    private MockHttpServletRequest chatRoomRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat-rooms/1/messages");
        request.setServletPath("/api/chat-rooms/1/messages");
        return request;
    }
}
