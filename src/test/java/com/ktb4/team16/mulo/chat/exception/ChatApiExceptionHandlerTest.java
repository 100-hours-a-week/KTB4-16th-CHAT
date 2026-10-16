package com.ktb4.team16.mulo.chat.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.auth.dto.ApiErrorResponse;
import com.ktb4.team16.mulo.chat.dto.response.ApiValidationErrorResponse;
import java.lang.reflect.Method;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ChatApiExceptionHandlerTest {

    private final ChatApiExceptionHandler handler = new ChatApiExceptionHandler();

    @Test
    @DisplayName("REST 비즈니스 예외는 기존 상태와 오류 본문을 유지한다")
    void mapsBusinessExceptionToExistingRestContract() {
        var response = handler.handleChatApiException(new ChatApiException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(
                new ApiErrorResponse("CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("REST 검증 실패는 기존 필드 오류 형식을 유지한다")
    void mapsValidationFailureToExistingFieldErrorContract() throws NoSuchMethodException {
        Method method = ValidationTarget.class.getDeclaredMethod("accept", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new ValidationTarget(), "request");
        bindingResult.rejectValue("chatRoomId", "INVALID_CHAT_ROOM_ID");
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        var response = handler.handleInvalidInput(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiValidationErrorResponse body = Objects.requireNonNull(response.getBody());
        assertThat(body.code()).isEqualTo("INVALID_INPUT_VALUE");
        assertThat(body.message()).isEqualTo("입력값을 확인해주세요.");
        assertThat(body.errors()).containsExactly(new ApiValidationErrorResponse.Error(
                "chatRoomId", "INVALID_CHAT_ROOM_ID", "올바른 채팅방 ID를 입력해주세요."));
    }

    @Test
    @DisplayName("예상하지 못한 REST 예외는 내부 정보를 숨긴다")
    void hidesUnexpectedExceptionDetails() {
        var response = handler.handleUnexpectedException(new IllegalStateException("internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    private static class ValidationTarget {
        private String chatRoomId;

        void accept(String chatRoomId) {
            this.chatRoomId = chatRoomId;
        }

        public String getChatRoomId() {
            return chatRoomId;
        }

        public void setChatRoomId(String chatRoomId) {
            this.chatRoomId = chatRoomId;
        }
    }
}
