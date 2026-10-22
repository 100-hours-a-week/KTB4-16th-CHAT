package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.dto.request.CreateChatRoomRequest;
import com.ktb4.team16.mulo.chat.repository.ChatRoomParticipantRepository;
import com.ktb4.team16.mulo.chat.repository.ChatRoomRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@EnabledIfEnvironmentVariable(named = "CHAT_DB_URL", matches = ".+")
class ChatRoomCreateConcurrencyIntegrationTest {

    private static final AtomicLong MATCH_ID_SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired
    private ChatRoomCreateService createService;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomParticipantRepository participantRepository;

    @Test
    @DisplayName("동일 matchId 동시 생성은 채팅방과 참가자 두 명만 저장한다")
    void createsOnlyOneRoomForConcurrentIdenticalRequests() throws Exception {
        long matchId = MATCH_ID_SEQUENCE.incrementAndGet();
        CreateChatRoomRequest request = new CreateChatRoomRequest(matchId,
                OffsetDateTime.now(ChatTime.KST).plusDays(2), List.of(
                new CreateChatRoomRequest.Participant(matchId + 1, "jettie"),
                new CreateChatRoomRequest.Participant(matchId + 2, "lulu")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> createAfterBarrier(request, ready, start));
            var second = executor.submit(() -> createAfterBarrier(request, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ChatRoomCreateService.Result firstResult = first.get(20, TimeUnit.SECONDS);
            ChatRoomCreateService.Result secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(firstResult.response().chatRoomId()).isEqualTo(secondResult.response().chatRoomId());
            assertThat(List.of(firstResult.created(), secondResult.created())).containsExactlyInAnyOrder(true, false);
            var room = chatRoomRepository.findByMatchId(matchId).orElseThrow();
            assertThat(participantRepository.countByChatRoomChatRoomId(room.getChatRoomId())).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            chatRoomRepository.findByMatchId(matchId).ifPresent(chatRoomRepository::delete);
        }
    }

    @Test
    @DisplayName("참가자 저장 실패 시 채팅방도 함께 롤백한다")
    void rollsBackRoomWhenParticipantInsertFails() {
        long matchId = MATCH_ID_SEQUENCE.incrementAndGet();
        CreateChatRoomRequest request = new CreateChatRoomRequest(matchId,
                OffsetDateTime.now(ChatTime.KST).plusDays(2), List.of(
                new CreateChatRoomRequest.Participant(matchId + 1, "nickname-too-long"),
                new CreateChatRoomRequest.Participant(matchId + 2, "lulu")));

        assertThatThrownBy(() -> createService.create(request))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(chatRoomRepository.findByMatchId(matchId)).isEmpty();
    }

    private ChatRoomCreateService.Result createAfterBarrier(
            CreateChatRoomRequest request, CountDownLatch ready, CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("테스트 시작 신호를 받지 못했습니다.");
        }
        return createService.create(request);
    }
}
