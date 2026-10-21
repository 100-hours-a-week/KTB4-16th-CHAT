package com.ktb4.team16.mulo.chat.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatMessageResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("직접 채팅 응답은 voteChoice를 추가하지 않고 그룹 응답은 선택값을 포함한다")
    void includesVoteChoiceOnlyWhenPresent() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-21T12:00:00+09:00");
        ChatMessageResponse direct = new ChatMessageResponse(
                "MESSAGE", 1L, 10L, UUID.randomUUID(), 2L, "direct", createdAt);
        ChatMessageResponse group = new ChatMessageResponse(
                "MESSAGE", 2L, 11L, UUID.randomUUID(), 3L, "group", createdAt, "B");

        String directJson = objectMapper.writeValueAsString(direct);
        String groupJson = objectMapper.writeValueAsString(group);

        assertThat(directJson).doesNotContain("voteChoice");
        assertThat(groupJson).contains("\"voteChoice\":\"B\"");
    }
}
