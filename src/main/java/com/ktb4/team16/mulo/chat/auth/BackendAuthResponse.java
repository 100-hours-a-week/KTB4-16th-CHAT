package com.ktb4.team16.mulo.chat.auth;

import java.time.OffsetDateTime;

public record BackendAuthResponse(Long userId, OffsetDateTime expiresAt) {
}
