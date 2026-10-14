package com.ktb4.team16.mulo.chat.auth;

public interface BackendTokenVerifier {

    BackendAuthResponse verify(String accessToken);
}
