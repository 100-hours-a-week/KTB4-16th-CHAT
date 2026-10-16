package com.ktb4.team16.mulo.chat.auth.security;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class RequiredAllowedOriginHandshakeInterceptor implements HandshakeInterceptor {

    private final List<String> allowedOrigins;

    public RequiredAllowedOriginHandshakeInterceptor(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        List<String> origins = request.getHeaders().get("Origin");
        if (origins == null || origins.size() != 1) {
            return reject(response);
        }
        String origin = origins.getFirst();
        if (origin == null || origin.isBlank() || !isValidOrigin(origin)
                || !allowedOrigins.contains(origin)) {
            return reject(response);
        }
        return true;
    }

    private boolean isValidOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
    }
}
