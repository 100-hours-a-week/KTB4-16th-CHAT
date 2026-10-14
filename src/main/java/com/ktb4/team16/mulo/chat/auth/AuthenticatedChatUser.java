package com.ktb4.team16.mulo.chat.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

public final class AuthenticatedChatUser {

    private AuthenticatedChatUser() {
    }

    public static Long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ChatPrincipal principal) {
            return principal.userId();
        }
        throw new AuthenticationCredentialsNotFoundException("인증된 사용자가 없습니다.");
    }
}
