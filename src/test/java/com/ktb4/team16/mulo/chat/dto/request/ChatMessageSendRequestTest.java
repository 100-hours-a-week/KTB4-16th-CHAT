package com.ktb4.team16.mulo.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatMessageSendRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsRequiredMessageFieldsWithinContentLimit() {
        ChatMessageSendRequest request = new ChatMessageSendRequest(UUID.randomUUID(), "안녕하세요");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(validator.validate(new ChatMessageSendRequest(UUID.randomUUID(), "a".repeat(500))))
                .isEmpty();
    }

    @Test
    void rejectsMissingIdBlankContentAndContentOverLimit() {
        assertThat(validator.validate(new ChatMessageSendRequest(null, "안녕하세요"))).hasSize(1);
        assertThat(validator.validate(new ChatMessageSendRequest(UUID.randomUUID(), "  "))).hasSize(1);
        assertThat(validator.validate(new ChatMessageSendRequest(UUID.randomUUID(), "a".repeat(501))))
                .hasSize(1);
    }
}
