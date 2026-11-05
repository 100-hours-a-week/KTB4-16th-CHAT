package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ktb4.team16.mulo.chat.dto.response.ChatRoomDetailResponse;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.exception.ChatApiException;
import com.ktb4.team16.mulo.chat.repository.ChatMessageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

class ChatMessageSyncServiceTest {
    private final ChatRoomRestService rooms = mock(ChatRoomRestService.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatMessageSyncService service = new ChatMessageSyncService(rooms, messages);

    @BeforeEach
    void setup() {
        var detail = mock(ChatRoomDetailResponse.class);
        when(detail.participants())
                .thenReturn(
                        List.of(new ChatRoomDetailResponse.Participant(17L, "one", null, false)));
        when(rooms.getDetail(1L, 17L)).thenReturn(detail);
        when(messages.findMaxMessageIdByRoomId(1L)).thenReturn(45L);
    }

    @Test
    void checkpointDoesNotPretendToReplayHistory() {
        var page = service.sync(1L, 17L, null, null);
        assertThat(page.messages()).isEmpty();
        assertThat(page.throughMessageId()).isEqualTo(45);
        assertThat(page.nextAfterMessageId()).isEqualTo(45);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void fixedUpperBoundAndTwentyItemPageDoNotSkipGaps() {
        var firstRows = LongStream.rangeClosed(1, 21).mapToObj(this::message).toList();
        when(messages.findSyncRange(eq(1L), eq(0L), eq(45L), any())).thenReturn(firstRows);
        var page = service.sync(1L, 17L, 0L, 45L);
        assertThat(page.messages()).hasSize(20);
        assertThat(page.nextAfterMessageId()).isEqualTo(20);
        assertThat(page.hasNext()).isTrue();
        var lastRows = List.of(message(42), message(45));
        when(messages.findSyncRange(eq(1L), eq(40L), eq(45L), any())).thenReturn(lastRows);
        var last = service.sync(1L, 17L, 40L, 45L);
        assertThat(last.messages()).extracting(x -> x.chatMessageId()).containsExactly(42L, 45L);
        assertThat(last.nextAfterMessageId()).isEqualTo(45);
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void rejectsInvalidBoundsAndUnauthorizedReads() {
        assertThatThrownBy(() -> service.sync(1L, 17L, null, 45L))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> service.sync(1L, 17L, 46L, null))
                .isInstanceOf(ChatApiException.class);
        assertThatThrownBy(() -> service.sync(1L, 17L, -1L, null))
                .isInstanceOf(ChatApiException.class);
        when(rooms.getDetail(1L, 99L))
                .thenThrow(
                        new ChatApiException(
                                com.ktb4.team16.mulo.chat.exception.ChatErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.sync(1L, 99L, 0L, null))
                .isInstanceOf(ChatApiException.class);
    }

    private ChatMessage message(long id) {
        var m = mock(ChatMessage.class);
        when(m.getChatMessageId()).thenReturn(id);
        when(m.getUserId()).thenReturn(17L);
        when(m.getClientMessageId()).thenReturn("id-" + id);
        when(m.getContent()).thenReturn("text");
        when(m.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 11, 5, 0, 0));
        return m;
    }
}
