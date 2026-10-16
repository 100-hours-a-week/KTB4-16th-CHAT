package com.ktb4.team16.mulo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompConnectAuthenticationInterceptorTest {

    private final BackendTokenVerifier tokenVerifier = mock(BackendTokenVerifier.class);
    private final WebSocketSessionRegistry sessionRegistry = mock(WebSocketSessionRegistry.class);
    private final StompConnectAuthenticationInterceptor interceptor =
            new StompConnectAuthenticationInterceptor(tokenVerifier, sessionRegistry, Clock.systemUTC());

    // CONNECT 이전 세션의 Heartbeat는 인터셉터에서 인증 실패로 거부합니다.
    @Test
    void rejectsHeartbeatFromUnauthenticatedSession() {
        Message<byte[]> heartbeat = heartbeat("session-1");

        assertThatThrownBy(() -> interceptor.preSend(heartbeat, null))
                .isInstanceOf(StompAuthenticationException.class)
                .extracting(exception -> ((StompAuthenticationException) exception).getCode())
                .isEqualTo("UNAUTHORIZED");
    }

    // 인증된 세션의 Heartbeat는 원본 메시지를 그대로 전달합니다.
    @Test
    void allowsHeartbeatFromAuthenticatedSession() {
        Message<byte[]> heartbeat = heartbeat("session-1");
        when(sessionRegistry.isAuthenticated("session-1")).thenReturn(true);

        assertThat(interceptor.preSend(heartbeat, null)).isSameAs(heartbeat);
    }

    // STOMP 명령이 없는 실제 Heartbeat 형태와 세션 ID를 가진 메시지를 만듭니다.
    private Message<byte[]> heartbeat(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[] {'\n'}, accessor.getMessageHeaders());
    }
}
