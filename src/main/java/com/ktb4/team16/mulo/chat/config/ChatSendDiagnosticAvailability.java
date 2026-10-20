package com.ktb4.team16.mulo.chat.config;

/** 현재 배포 환경에서 제한된 STOMP SEND 진단 응답을 제공할 수 있는지 나타낸다. */
public record ChatSendDiagnosticAvailability(boolean enabled) {
}
