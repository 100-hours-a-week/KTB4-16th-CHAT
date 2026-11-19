package com.ktb4.team16.mulo.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 닉네임 상태 전환 설정을 Spring 구성에 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatNicknameStateProperties.class)
public class ChatNicknameStateConfiguration {
}
