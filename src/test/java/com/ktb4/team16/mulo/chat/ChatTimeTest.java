package com.ktb4.team16.mulo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ChatTimeTest {

    @Test
    void usesKstForFixedClocksRegardlessOfClockZone() {
        Instant instant = Instant.parse("2026-10-14T15:00:00Z");
        Clock utcClock = Clock.fixed(instant, ZoneOffset.UTC);
        Clock seoulClock = Clock.fixed(instant, ChatTime.KST);

        assertThat(ChatTime.now(utcClock)).isEqualTo(LocalDateTime.parse("2026-10-15T00:00:00"));
        assertThat(ChatTime.now(seoulClock)).isEqualTo(ChatTime.now(utcClock));
    }

    @Test
    void convertsLocalDateTimeToOffsetDateTimeWithKstOffset() {
        OffsetDateTime value = ChatTime.toOffsetDateTime(LocalDateTime.parse("2026-10-15T00:00:00"));

        assertThat(value).isEqualTo(OffsetDateTime.parse("2026-10-15T00:00:00+09:00"));
    }
}
