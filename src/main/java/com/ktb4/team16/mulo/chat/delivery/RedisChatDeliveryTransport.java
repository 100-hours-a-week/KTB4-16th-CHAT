package com.ktb4.team16.mulo.chat.delivery;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.*;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Pub/Sub 연결·재구독·상태 점검·종료의 소유자다. */
public final class RedisChatDeliveryTransport implements ChatDeliveryPublisher, SmartLifecycle {
    private final ChatRedisProperties properties;
    private final ChatDeliveryCodec codec;
    private final ChatLocalDeliveryDispatcher dispatcher;
    private final ChatDeliveryLifecycle lifecycle;
    private final MeterRegistry meters;
    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> publisher;
    private volatile StatefulRedisPubSubConnection<String, String> subscriber;
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "chat-delivery-monitor");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean running;
    private volatile boolean subscribed;
    private long healthySince;
    private long seenFailureEpoch;

    /** 발행 연결과 구독 전용 연결이 같은 TLS·ACL·기한을 사용하도록 구성한다. */
    public RedisChatDeliveryTransport(
            ChatRedisProperties p,
            ChatDeliveryCodec codec,
            ChatLocalDeliveryDispatcher dispatcher,
            ChatDeliveryLifecycle lifecycle,
            MeterRegistry meters) {
        this.properties = p;
        this.codec = codec;
        this.dispatcher = dispatcher;
        this.lifecycle = lifecycle;
        this.meters = meters;
        var uri =
                RedisURI.Builder.redis(p.host(), p.port())
                        .withSsl(p.tls())
                        .withTimeout(p.connectTimeout());
        if (p.password() != null && !p.password().isBlank()) {
            if (p.username() != null && !p.username().isBlank())
                uri.withAuthentication(p.username(), p.password());
            else uri.withPassword(p.password().toCharArray());
        }
        client = RedisClient.create(uri.build());
        client.setOptions(
                ClientOptions.builder()
                        .socketOptions(
                                SocketOptions.builder().connectTimeout(p.connectTimeout()).build())
                        .timeoutOptions(TimeoutOptions.enabled(p.commandTimeout()))
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build());
        meters.gauge("mulo.chat.delivery.ready", lifecycle, s -> s.isReady() ? 1 : 0);
    }

    /** 구독 ACK 이후에만 사용 가능한 상태로 승격한다. */
    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        monitor.scheduleWithFixedDelay(this::probe, 0, 1, TimeUnit.SECONDS);
    }

    /** Redis 구독과 writer 접근이 안정적으로 복구되었는지 확인한다. */
    private void probe() {
        if (!running || lifecycle.isStopped()) return;
        try {
            if (publisher == null) {
                publisher = client.connect();
                publisher.setTimeout(properties.commandTimeout());
            }
            if (subscriber == null) {
                var connection = client.connectPubSub();
                subscriber = connection;
                connection.addListener(
                        new RedisPubSubAdapter<String, String>() {
                            @Override
                            public void subscribed(String channel, long count) {
                                if (channel.equals(properties.channel())) subscribed = true;
                            }

                            @Override
                            public void message(String channel, String json) {
                                if (!channel.equals(properties.channel())) return;
                                try {
                                    dispatcher.submit(codec.decode(json));
                                } catch (RuntimeException e) {
                                    meters.counter("mulo.chat.delivery.decode.failure").increment();
                                    lifecycle.onDeliveryFailure();
                                }
                            }
                        });
                connection.addListener(
                        new RedisConnectionStateListener() {
                            @Override
                            public void onRedisDisconnected(RedisChannelHandler<?, ?> c) {
                                subscribed = false;
                                lifecycle.onDeliveryFailure();
                            }
                        });
                connection.async().subscribe(properties.channel());
            }
            publisher.sync().ping();
            if (!subscribed) throw new IllegalStateException("Subscription not confirmed");
            if (lifecycle.isReady()) return;
            if (seenFailureEpoch != lifecycle.failureEpoch()) {
                seenFailureEpoch = lifecycle.failureEpoch();
                healthySince = 0;
            }
            if (!dispatcher.isIdle()) {
                healthySince = 0;
                return;
            }
            if (healthySince == 0) healthySince = System.nanoTime();
            if (System.nanoTime() - healthySince >= Duration.ofSeconds(5).toNanos())
                lifecycle.markReady(seenFailureEpoch);
        } catch (RuntimeException failure) {
            healthySince = 0;
            lifecycle.onDeliveryFailure();
        }
    }

    /** commit 성공을 보존하고 전파 실패는 DB 복구가 회수하도록 기록한다. */
    @Override
    public void publish(Object response, List<Long> recipients) {
        try {
            var connection = publisher;
            if (connection == null) throw new IllegalStateException("Publisher unavailable");
            long count =
                    connection
                            .sync()
                            .publish(
                                    properties.channel(),
                                    codec.encode(codec.create(response, recipients)));
            if (count == 0) meters.counter("mulo.chat.delivery.publish.zero").increment();
        } catch (RuntimeException failure) {
            meters.counter("mulo.chat.delivery.publish.failure").increment();
            lifecycle.onDeliveryFailure();
        }
    }

    /** 애플리케이션 종료 시 상태 점검·워커·Redis 연결을 정리한다. */
    @Override
    public void stop() {
        if (!running) return;
        running = false;
        lifecycle.stopped();
        monitor.shutdownNow();
        dispatcher.stop();
        var sub = subscriber;
        if (sub != null) sub.close();
        var pub = publisher;
        if (pub != null) pub.close();
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2000;
    }
}
