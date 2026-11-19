package com.ktb4.team16.mulo.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 그룹 입장에서 버전 상태를 조회하고 사용하는 전환 설정이다. */
@ConfigurationProperties(prefix = "chat.nickname-state")
public record ChatNicknameStateProperties(boolean enabled) {
}
