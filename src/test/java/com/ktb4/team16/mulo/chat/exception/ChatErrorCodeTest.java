package com.ktb4.team16.mulo.chat.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatErrorCodeTest {

    @Test
    @DisplayName("오류 코드는 HTTP 상태와 기본 메시지를 함께 관리한다")
    void keepsStatusAndDefaultMessageTogether() {
        assertThat(ChatErrorCode.CHAT_ROOM_NOT_FOUND.code()).isEqualTo("CHAT_ROOM_NOT_FOUND");
        assertThat(ChatErrorCode.CHAT_ROOM_NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ChatErrorCode.CHAT_ROOM_NOT_FOUND.message()).isEqualTo("채팅방을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("같은 공개 오류 코드의 REST 상태 차이를 유지한다")
    void preservesContextSpecificStatusForAlreadyLeft() {
        assertThat(ChatErrorCode.CHAT_ALREADY_LEFT.code()).isEqualTo("CHAT_ALREADY_LEFT");
        assertThat(ChatErrorCode.CHAT_ALREADY_LEFT.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ChatErrorCode.CHAT_ALREADY_LEFT_FORBIDDEN.code()).isEqualTo("CHAT_ALREADY_LEFT");
        assertThat(ChatErrorCode.CHAT_ALREADY_LEFT_FORBIDDEN.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("내부 채팅방 생성 만료와 충돌은 명세의 409 오류 코드를 사용한다")
    void mapsInternalChatRoomCreationConflicts() {
        assertThat(ChatErrorCode.CHAT_ROOM_CREATE_EXPIRED.code()).isEqualTo("CHAT_ROOM_EXPIRED");
        assertThat(ChatErrorCode.CHAT_ROOM_CREATE_EXPIRED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ChatErrorCode.CHAT_ROOM_CONFLICT.code()).isEqualTo("CHAT_ROOM_CONFLICT");
        assertThat(ChatErrorCode.CHAT_ROOM_CONFLICT.status()).isEqualTo(HttpStatus.CONFLICT);
    }
}
