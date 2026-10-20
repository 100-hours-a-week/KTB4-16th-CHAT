package com.ktb4.team16.mulo.chat.controller;

import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.ktb4.team16.mulo.chat.auth.security.ChatPrincipal;
import com.ktb4.team16.mulo.chat.config.ChatSendDiagnosticAvailability;
import com.ktb4.team16.mulo.chat.dto.request.ChatMessageSendRequest;
import com.ktb4.team16.mulo.chat.dto.response.ChatMessageErrorResponse;
import com.ktb4.team16.mulo.chat.exception.ChatErrorCode;
import com.ktb4.team16.mulo.chat.exception.ChatMessageSendException;
import com.ktb4.team16.mulo.chat.exception.ChatSendDiagnostic;
import com.ktb4.team16.mulo.chat.service.ChatMessageSendService;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.BeanPropertyBindingResult;

class ChatMessageStompControllerTest {

    private final ChatMessageSendService sendService = Mockito.mock(ChatMessageSendService.class);
    private final ChatMessageStompController controller = new ChatMessageStompController(
            sendService, new ChatSendDiagnosticAvailability(false));

    @Test
    @DisplayName("인증된 사용자와 검증된 요청을 메시지 전송 서비스에 전달한다")
    void delegatesChatRoomIdAuthenticatedUserAndValidatedRequestToService() {
        ChatMessageSendRequest request = new ChatMessageSendRequest(UUID.randomUUID(), "안녕하세요");

        controller.sendMessage(23L, request, new ChatPrincipal(17L));

        verify(sendService).send(23L, 17L, request);
    }

    @Test
    @DisplayName("메시지 처리 예외는 해당 오류 코드와 요청 식별자를 전달한다")
    void mapsBusinessErrorToStompErrorResponse() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageErrorResponse response = controller.handleSendError(
                new ChatMessageSendException(ChatErrorCode.CLIENT_MESSAGE_ID_CONFLICT, clientMessageId));

        assertThat(response).isEqualTo(new ChatMessageErrorResponse(
                "CLIENT_MESSAGE_ID_CONFLICT", "이미 다른 메시지에 사용된 clientMessageId입니다.", clientMessageId));
    }

    @Test
    @DisplayName("격리 개발 진단이 활성화되면 제한된 사유만 오류 응답에 포함한다")
    void includesDiagnosticDetailsWhenEnabled() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageStompController diagnosticController = new ChatMessageStompController(
                sendService, new ChatSendDiagnosticAvailability(true));
        ChatMessageSendException exception = new ChatMessageSendException(
                ChatErrorCode.CHAT_SEND_FORBIDDEN,
                clientMessageId,
                new ChatSendDiagnostic(ChatSendDiagnostic.Stage.PRECHECK,
                        ChatSendDiagnostic.Reason.PEER_NOT_ACTIVE));

        ChatMessageErrorResponse response = diagnosticController.handleSendError(exception);

        assertThat(response.debug()).isEqualTo(new ChatMessageErrorResponse.Debug("precheck", "PEER_NOT_ACTIVE"));
        assertThat(new ObjectMapper().writeValueAsString(response))
                .contains("\"debug\":{\"stage\":\"precheck\",\"reason\":\"PEER_NOT_ACTIVE\"}")
                .doesNotContain("userId", "nickname", "participantCount", "content");
    }

    @Test
    @DisplayName("진단이 비활성화되면 debug 필드 없이 기존 오류 JSON을 반환한다")
    void omitsDiagnosticDetailsWhenDisabled() throws Exception {
        ChatMessageSendException exception = new ChatMessageSendException(
                ChatErrorCode.CHAT_SEND_FORBIDDEN,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ChatSendDiagnostic(ChatSendDiagnostic.Stage.TRANSACTION,
                        ChatSendDiagnostic.Reason.SENDER_LEFT));

        ChatMessageErrorResponse response = controller.handleSendError(exception);
        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(response.debug()).isNull();
        assertThat(json).doesNotContain("debug");
        assertThat(json).contains("code", "message", "clientMessageId");
    }

    @Test
    @DisplayName("모더레이션 차단 코드를 기존 사용자 오류 응답으로 전달한다")
    void mapsModerationBlockToStompErrorResponse() {
        UUID clientMessageId = UUID.randomUUID();

        ChatMessageErrorResponse response = controller.handleSendError(
                new ChatMessageSendException(ChatErrorCode.PROHIBITED_WORD, clientMessageId));

        assertThat(response.code()).isEqualTo("PROHIBITED_WORD");
        assertThat(response.clientMessageId()).isEqualTo(clientMessageId);
    }

    @Test
    @DisplayName("본문 길이 검증 실패는 구체적인 오류 코드로 전달한다")
    void mapsContentLengthViolationToSpecificError() throws NoSuchMethodException {
        Method method = ChatMessageStompController.class.getDeclaredMethod(
                "sendMessage", Long.class, ChatMessageSendRequest.class, ChatPrincipal.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new ChatMessageSendRequest(UUID.randomUUID(), "x".repeat(501)), "request");
        bindingResult.rejectValue("content", "Size");
        Message<ChatMessageSendRequest> message = MessageBuilder.withPayload(
                new ChatMessageSendRequest(UUID.randomUUID(), "x".repeat(501))).build();
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                message, new MethodParameter(method, 1), bindingResult);

        ChatMessageErrorResponse response = controller.handleSendError(exception);

        assertThat(response.code()).isEqualTo("MESSAGE_TOO_LONG");
        assertThat(response.message()).isEqualTo("메시지는 500자 이하여야 합니다.");
    }

    @Test
    @DisplayName("요청 변환 실패는 입력 오류로 전달한다")
    void mapsMalformedPayloadToInputError() {
        ChatMessageErrorResponse response = controller.handleSendError(
                new MessageConversionException("invalid payload"));

        assertThat(response.code()).isEqualTo("INVALID_INPUT_VALUE");
        assertThat(response.message()).isEqualTo("입력값을 확인해주세요.");
    }

    @Test
    @DisplayName("예상하지 못한 STOMP 예외는 내부 정보를 숨긴다")
    void hidesUnexpectedStompExceptionDetails() {
        ChatMessageErrorResponse response = controller.handleSendError(
                new IllegalStateException("internal detail"));

        assertThat(response).isEqualTo(new ChatMessageErrorResponse(
                "STOMP_ERROR", "메시지를 전송할 수 없습니다.", null));
    }
}
