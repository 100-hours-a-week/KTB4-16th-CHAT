package com.ktb4.team16.mulo.chat.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("chat.delivery.redis")
public record ChatRedisProperties(
        String host,
        int port,
        boolean tls,
        String username,
        String password,
        String environment,
        Duration connectTimeout,
        Duration commandTimeout) {
    /** 운영 공유 채널을 필수로 지정하고 잘못된 주소·기한을 기동 전에 거절한다. */
    public ChatRedisProperties {
        if (host == null
                || host.isBlank()
                || port < 1
                || port > 65535
                || environment == null
                || !environment.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || connectTimeout == null
                || connectTimeout.isNegative()
                || connectTimeout.isZero()
                || commandTimeout == null
                || commandTimeout.isNegative()
                || commandTimeout.isZero())
            throw new IllegalArgumentException("Invalid CHAT Redis configuration");
    }

    /** 비밀 속성을 실수로 로그에 출력하지 않는다. */
    @Override
    public String toString() {
        return "ChatRedisProperties[redacted]";
    }

    public String channel() {
        return "mulo:chat:" + environment + ":delivery:v1";
    }
}
