package com.ktb4.team16.mulo.chat.cursor;

import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ChatMessageCursorCodec {

    public Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            long chatMessageId = Long.parseLong(parts[1]);
            if (chatMessageId <= 0) {
                throw new IllegalArgumentException();
            }
            return new Cursor(LocalDateTime.parse(parts[0]), chatMessageId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "올바른 커서를 입력해주세요.");
        }
    }

    public String encode(LocalDateTime createdAt, Long chatMessageId) {
        String value = createdAt + "|" + chatMessageId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public record Cursor(LocalDateTime createdAt, Long chatMessageId) {
    }
}
