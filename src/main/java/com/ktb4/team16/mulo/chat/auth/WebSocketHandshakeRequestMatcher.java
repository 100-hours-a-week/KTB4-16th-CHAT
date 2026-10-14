package com.ktb4.team16.mulo.chat.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

@Component
public class WebSocketHandshakeRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        String expectedPath = request.getContextPath() + "/ws";
        return "GET".equalsIgnoreCase(request.getMethod())
                && expectedPath.equals(request.getRequestURI())
                && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"))
                && containsUpgradeToken(request.getHeaders("Connection"));
    }

    private boolean containsUpgradeToken(Enumeration<String> values) {
        return Collections.list(values).stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch("upgrade"::equals);
    }
}
