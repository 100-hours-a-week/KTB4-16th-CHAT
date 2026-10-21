package com.ktb4.team16.mulo.chat.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroupChatJoinOrCreateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("오늘 날짜와 양수 식별자, A/B 선택을 가진 요청을 허용한다")
    void acceptsValidRequest() {
        GroupChatJoinOrCreateRequest request = new GroupChatJoinOrCreateRequest(
                LocalDate.of(2026, 10, 21), 88L, 17L, "nickname", "A");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("잘못된 A/B 선택과 누락된 식별자를 거부한다")
    void rejectsInvalidChoiceAndMissingFields() {
        GroupChatJoinOrCreateRequest invalidChoice = new GroupChatJoinOrCreateRequest(
                LocalDate.of(2026, 10, 21), 88L, 17L, "nickname", "C");
        GroupChatJoinOrCreateRequest missingFields = new GroupChatJoinOrCreateRequest(
                null, 0L, null, "", null);

        assertThat(validator.validate(invalidChoice)).isNotEmpty();
        assertThat(validator.validate(missingFields)).isNotEmpty();
    }
}
