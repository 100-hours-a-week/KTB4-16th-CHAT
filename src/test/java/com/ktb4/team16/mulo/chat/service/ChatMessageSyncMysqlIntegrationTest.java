package com.ktb4.team16.mulo.chat.service;

import static org.assertj.core.api.Assertions.*;

import com.ktb4.team16.mulo.chat.ChatTime;
import com.ktb4.team16.mulo.chat.entity.ChatRoom;
import com.ktb4.team16.mulo.chat.entity.ChatRoomParticipant;
import com.ktb4.team16.mulo.chat.entity.ChatMessage;
import com.ktb4.team16.mulo.chat.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
class ChatMessageSyncMysqlIntegrationTest {
    @Autowired ChatRoomParticipantRepository participants;
    @Autowired ChatRoomRestService rest;
    @Autowired ChatMessageSyncService sync;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatRoomAccessService access;
    @Autowired PlatformTransactionManager manager;

    @Test
    void recovers45CommittedMessagesAndSerializesSameRoomIdAllocation() throws Exception {
        Long room = createGroupRoomWithParticipants();
        rest.enter(room, 17L);
        rest.enter(room, 18L);
        var tx = new TransactionTemplate(manager);
        try {
            long checkpoint = sync.sync(room, 17L, null, null).throughMessageId();
            assertThat(checkpoint).isZero();
            for (int i = 0; i < 45; i++) {
                tx.executeWithoutResult(
                        status -> {
                            access.findParticipantsForUpdate(room);
                            messages.saveAndFlush(
                                    ChatMessage.create(
                                            rooms.getReferenceById(room),
                                            17L,
                                            UUID.randomUUID().toString(),
                                            "message"));
                        });
            }
            var ids = new ArrayList<Long>();
            Long after = 0L, upper = null;
            do {
                var page = sync.sync(room, 17L, after, upper);
                if (upper == null) upper = page.throughMessageId();
                ids.addAll(page.messages().stream().map(m -> m.chatMessageId()).toList());
                after = page.nextAfterMessageId();
                if (!page.hasNext()) break;
            } while (true);
            assertThat(ids).hasSize(45);
            assertThat(new HashSet<>(ids)).hasSize(45);
            assertThat(ids).isSorted();
            CountDownLatch allocated = new CountDownLatch(1),
                    release = new CountDownLatch(1),
                    trying = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var a =
                        executor.submit(
                                () ->
                                        tx.execute(
                                                status -> {
                                                    access.findParticipantsForUpdate(room);
                                                    var saved =
                                                            messages.saveAndFlush(
                                                                    ChatMessage.create(
                                                                            rooms.getReferenceById(
                                                                                    room),
                                                                            17L,
                                                                            UUID.randomUUID()
                                                                                    .toString(),
                                                                            "delayed"));
                                                    allocated.countDown();
                                                    try {
                                                        release.await(5, TimeUnit.SECONDS);
                                                    } catch (InterruptedException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                    return saved.getChatMessageId();
                                                }));
                assertThat(allocated.await(5, TimeUnit.SECONDS)).isTrue();
                var c =
                        executor.submit(
                                () -> {
                                    trying.countDown();
                                    return tx.execute(
                                            status -> {
                                                access.findParticipantsForUpdate(room);
                                                return messages.saveAndFlush(
                                                                ChatMessage.create(
                                                                        rooms.getReferenceById(
                                                                                room),
                                                                        18L,
                                                                        UUID.randomUUID()
                                                                                .toString(),
                                                                        "next"))
                                                        .getChatMessageId();
                                            });
                                });
                assertThat(trying.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> c.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(c.get(5, TimeUnit.SECONDS)).isGreaterThan(a.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertThat(sync.sync(room, 17L, upper, null).messages()).hasSize(2);
        } finally {
            rooms.deleteById(room);
        }
    }

    /** 메시지 동기화 검증을 위해 GROUP 방과 두 참가자를 한 트랜잭션으로 커밋한다. */
    private Long createGroupRoomWithParticipants() {
        return new TransactionTemplate(manager).execute(status -> {
            var date = ChatTime.now().toLocalDate();
            var room = rooms.saveAndFlush(ChatRoom.createGroup(
                    date, System.nanoTime(), 1, OffsetDateTime.now(ChatTime.KST).plusDays(1)));
            participants.saveAllAndFlush(List.of(
                    ChatRoomParticipant.createGroup(room, 17L, "one", date, "A"),
                    ChatRoomParticipant.createGroup(room, 18L, "two", date, "B")));
            return room.getChatRoomId();
        });
    }
}
