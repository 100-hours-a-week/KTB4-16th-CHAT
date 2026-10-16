package com.ktb4.team16.mulo.chat.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatMessageCursorCodecTest {

    private final ChatMessageCursorCodec codec = new ChatMessageCursorCodec();

    @Test
    void preservesBase64UrlCreatedAtAndMessageIdFormat() {
        LocalDateTime createdAt = LocalDateTime.parse("2026-10-13T15:30:00");

        String cursor = codec.encode(createdAt, 301L);
        String decodedValue = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);

        assertThat(decodedValue).isEqualTo("2026-10-13T15:30|301");
        assertThat(codec.decode(cursor)).isEqualTo(new ChatMessageCursorCodec.Cursor(createdAt, 301L));
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode(" ")).isNull();
    }

    @Test
    void rejectsMalformedCursorWithExistingApiContract() {
        assertThatThrownBy(() -> codec.decode("invalid"))
                .isInstanceOf(ChatApiException.class)
                .satisfies(exception -> {
                    ChatApiException apiException = (ChatApiException) exception;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.getCode()).isEqualTo("INVALID_CURSOR");
                    assertThat(apiException.getMessage()).isEqualTo("올바른 커서를 입력해주세요.");
                });
    }
}
