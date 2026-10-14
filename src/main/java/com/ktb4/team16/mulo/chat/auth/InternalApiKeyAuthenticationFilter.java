package com.ktb4.team16.mulo.chat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final BackendAuthProperties properties;
    private final ApiAuthenticationErrorHandler errorHandler;

    public InternalApiKeyAuthenticationFilter(
            BackendAuthProperties properties,
            ApiAuthenticationErrorHandler errorHandler
    ) {
        this.properties = properties;
        this.errorHandler = errorHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !(requestPath.equals("/internal") || requestPath.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (properties.internalApiKey() == null || properties.internalApiKey().isBlank()) {
            errorHandler.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "INTERNAL_AUTH_UNAVAILABLE", "내부 인증 설정을 사용할 수 없습니다.");
            return;
        }

        String apiKey = request.getHeader("X-Internal-Api-Key");
        if (!matchesApiKey(apiKey)) {
            errorHandler.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                "internal-service",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"))
        );
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean matchesApiKey(String providedApiKey) {
        return providedApiKey != null
                && MessageDigest.isEqual(
                        properties.internalApiKey().getBytes(StandardCharsets.UTF_8),
                        providedApiKey.getBytes(StandardCharsets.UTF_8)
                );
    }
}
