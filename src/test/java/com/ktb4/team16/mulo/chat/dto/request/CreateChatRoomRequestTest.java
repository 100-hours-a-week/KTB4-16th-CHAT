package com.ktb4.team16.mulo.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateChatRoomRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTwoValidParticipants() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                50L,
                OffsetDateTime.parse("2026-10-14T00:00:00+09:00"),
                List.of(
                        new CreateChatRoomRequest.Participant(17L, "jettie"),
                        new CreateChatRoomRequest.Participant(28L, "lulu")
                )
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsInvalidRequestFields() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                0L,
                null,
                List.of(new CreateChatRoomRequest.Participant(0L, ""))
        );

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsMissingExpiresAt() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                50L,
                null,
                List.of(
                        new CreateChatRoomRequest.Participant(17L, "jettie"),
                        new CreateChatRoomRequest.Participant(28L, "lulu")
                )
        );

        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("참가자 수가 두 명이 아니면 요청 검증에 실패한다")
    void rejectsParticipantCountsOtherThanTwo() {
        CreateChatRoomRequest noParticipants = new CreateChatRoomRequest(
                50L, OffsetDateTime.parse("2026-10-14T00:00:00+09:00"), List.of());
        CreateChatRoomRequest threeParticipants = new CreateChatRoomRequest(
                51L,
                OffsetDateTime.parse("2026-10-14T00:00:00+09:00"),
                List.of(new CreateChatRoomRequest.Participant(17L, "jettie"),
                        new CreateChatRoomRequest.Participant(28L, "lulu"),
                        new CreateChatRoomRequest.Participant(39L, "mulo")));

        assertThat(validator.validate(noParticipants)).isNotEmpty();
        assertThat(validator.validate(threeParticipants)).isNotEmpty();
    }
}
