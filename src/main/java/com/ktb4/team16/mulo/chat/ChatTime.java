package com.ktb4.team16.mulo.chat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class ChatTime {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Clock SYSTEM_CLOCK = Clock.systemUTC();

    private ChatTime() {
    }

    public static LocalDateTime now() {
        return now(SYSTEM_CLOCK);
    }

    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock.withZone(KST));
    }

    public static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value.atZone(KST).toOffsetDateTime();
    }
}
