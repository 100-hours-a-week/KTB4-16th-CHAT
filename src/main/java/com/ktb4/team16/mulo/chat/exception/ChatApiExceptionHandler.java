package com.ktb4.team16.mulo.chat.exception;

import com.ktb4.team16.mulo.chat.auth.ApiErrorResponse;
import com.ktb4.team16.mulo.chat.dto.response.ApiValidationErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ChatApiExceptionHandler {

    @ExceptionHandler(ChatApiException.class)
    ResponseEntity<ApiErrorResponse> handleChatApiException(ChatApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiValidationErrorResponse> handleInvalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiValidationErrorResponse(
                "INVALID_INPUT_VALUE",
                "입력값을 확인해주세요.",
                List.of(new ApiValidationErrorResponse.Error(
                        "chatRoomId", "INVALID_CHAT_ROOM_ID", "올바른 채팅방 ID를 입력해주세요."))
        ));
    }
}
