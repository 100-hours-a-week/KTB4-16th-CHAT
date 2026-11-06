package com.ktb4.team16.mulo.chat.delivery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 구독 준비 이전과 종료 중인 태스크의 새 연결을 거절한다. */
public final class ChatDeliveryHandshakeInterceptor implements HandshakeInterceptor {
    private final ObjectProvider<ChatDeliveryLifecycle> lifecycle;

    /** 로컬 모드와 기존 인증 타이머 의존성을 유지하기 위해 상태를 지연 조회한다. */
    public ChatDeliveryHandshakeInterceptor(ObjectProvider<ChatDeliveryLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attrs) {
        var state = lifecycle.getIfAvailable();
        if (state == null || state.isReady()) return true;
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Exception exception) {}
}
