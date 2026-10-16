package com.ktb4.team16.mulo.chat.exception;

import com.ktb4.team16.mulo.chat.auth.dto.ApiErrorResponse;
import com.ktb4.team16.mulo.chat.dto.response.ApiValidationErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ChatApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatApiExceptionHandler.class);

    @ExceptionHandler(ChatApiException.class)
    ResponseEntity<ApiErrorResponse> handleChatApiException(ChatApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiErrorResponse(exception.getErrorCode().code(), exception.getMessage()));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiValidationErrorResponse> handleInvalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiValidationErrorResponse(
                ChatErrorCode.INVALID_INPUT_VALUE.code(),
                ChatErrorCode.INVALID_INPUT_VALUE.message(),
                List.of(new ApiValidationErrorResponse.Error(
                        "chatRoomId", ChatErrorCode.INVALID_CHAT_ROOM_ID.code(),
                        ChatErrorCode.INVALID_CHAT_ROOM_ID.message()))
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleResourceNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(ChatErrorCode.RESOURCE_NOT_FOUND.status())
                .body(new ApiErrorResponse(ChatErrorCode.RESOURCE_NOT_FOUND.code(),
                        ChatErrorCode.RESOURCE_NOT_FOUND.message()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return errorResponse(ChatErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return errorResponse(ChatErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return errorResponse(ChatErrorCode.NOT_ACCEPTABLE);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("처리되지 않은 예외", exception);
        return errorResponse(ChatErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> errorResponse(ChatErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status())
                .body(new ApiErrorResponse(errorCode.code(), errorCode.message()));
    }
}
