package com.ktb4.team16.mulo.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateChatRoomRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTwoValidParticipants() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                50L,
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
                List.of(new CreateChatRoomRequest.Participant(0L, ""))
        );

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
