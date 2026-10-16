package com.ktb4.team16.mulo.chat.auth.security;

import java.security.Principal;

public record ChatPrincipal(Long userId) implements Principal {

    public ChatPrincipal {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
