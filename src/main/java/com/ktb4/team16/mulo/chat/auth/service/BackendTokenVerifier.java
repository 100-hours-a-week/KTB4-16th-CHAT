package com.ktb4.team16.mulo.chat.auth.service;

import com.ktb4.team16.mulo.chat.auth.dto.BackendAuthResponse;

public interface BackendTokenVerifier {

    BackendAuthResponse verify(String accessToken);
}
