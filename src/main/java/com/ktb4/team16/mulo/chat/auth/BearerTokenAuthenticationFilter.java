package com.ktb4.team16.mulo.chat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final BackendTokenVerifier tokenVerifier;
    private final ApiAuthenticationErrorHandler errorHandler;
    private final WebSocketHandshakeRequestMatcher webSocketHandshakeMatcher;

    public BearerTokenAuthenticationFilter(
            BackendTokenVerifier tokenVerifier,
            ApiAuthenticationErrorHandler errorHandler,
            WebSocketHandshakeRequestMatcher webSocketHandshakeMatcher
    ) {
        this.tokenVerifier = tokenVerifier;
        this.errorHandler = errorHandler;
        this.webSocketHandshakeMatcher = webSocketHandshakeMatcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !(requestPath.equals("/api/chat-rooms") || requestPath.startsWith("/api/chat-rooms/"))
                || webSocketHandshakeMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String accessToken = extractBearerToken(request.getHeader("Authorization"));
        if (accessToken == null) {
            errorHandler.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED", "인증이 필요합니다.");
            return;
        }

        try {
            BackendAuthResponse verified = tokenVerifier.verify(accessToken);
            var authentication = new UsernamePasswordAuthenticationToken(
                    new ChatPrincipal(verified.userId()),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_CHAT_USER"))
            );
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (BackendAuthException exception) {
            errorHandler.write(response, exception.getStatus(), exception.getCode(), exception.getResponseMessage());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
