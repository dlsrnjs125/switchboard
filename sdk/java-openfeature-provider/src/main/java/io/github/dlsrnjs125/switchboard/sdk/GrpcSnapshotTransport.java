package io.github.dlsrnjs125.switchboard.sdk;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.NackRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ResyncRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

public final class GrpcSnapshotTransport implements SnapshotTransport {
    private final SwitchboardProviderConfig config;
    private final ManagedChannel channel;
    private final SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub async;
    private final SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub blocking;
    private final ScheduledExecutorService scheduler;
    private final ReconnectBackoff reconnectBackoff;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile LongSupplier lastAppliedVersion;
    private volatile Listener listener;

    public GrpcSnapshotTransport(SwitchboardProviderConfig config) {
        this(config,
                ManagedChannelBuilder.forTarget(config.endpoint()).usePlaintext().build(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "switchboard-grpc-reconnect");
                    thread.setDaemon(true);
                    return thread;
                }),
                Math::random);
    }

    GrpcSnapshotTransport(
            SwitchboardProviderConfig config,
            ManagedChannel channel,
            ScheduledExecutorService scheduler,
            DoubleSupplier random) {
        this.config = config;
        this.channel = channel;
        this.scheduler = scheduler;
        this.reconnectBackoff = new ReconnectBackoff(
                config.initialReconnectBackoff(), config.maxReconnectBackoff(),
                config.reconnectJitter(), random);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + config.bearerCredential());
        var interceptor = MetadataUtils.newAttachHeadersInterceptor(headers);
        this.async = SnapshotDistributionServiceGrpc.newStub(channel).withInterceptors(interceptor);
        this.blocking = SnapshotDistributionServiceGrpc.newBlockingStub(channel).withInterceptors(interceptor);
    }

    @Override
    public void start(LongSupplier lastAppliedVersion, Listener listener) {
        this.lastAppliedVersion = Objects.requireNonNull(lastAppliedVersion, "lastAppliedVersion");
        this.listener = Objects.requireNonNull(listener, "listener");
        connect();
    }

    @Override
    public void acknowledge(long snapshotVersion, String checksum) {
        scheduler.execute(() -> {
            try {
                blocking.withDeadlineAfter(5, TimeUnit.SECONDS).acknowledge(AckRequest.newBuilder()
                        .setClientApplicationKey(config.clientApplicationKey())
                        .setEnvironmentKey(config.environmentKey())
                        .setSnapshotVersion(snapshotVersion)
                        .setChecksum(checksum)
                        .build());
            } catch (RuntimeException exception) {
                listener.onDisconnected(exception);
            }
        });
    }

    @Override
    public void reject(long snapshotVersion, String reasonCode, String detail) {
        scheduler.execute(() -> {
            try {
                blocking.withDeadlineAfter(5, TimeUnit.SECONDS).reject(NackRequest.newBuilder()
                        .setClientApplicationKey(config.clientApplicationKey())
                        .setEnvironmentKey(config.environmentKey())
                        .setSnapshotVersion(snapshotVersion)
                        .setReasonCode(reasonCode)
                        .setDetail(detail == null ? "" : detail)
                        .build());
            } catch (RuntimeException exception) {
                listener.onDisconnected(exception);
            }
        });
    }

    @Override
    public void requestResync(long lastAppliedVersion, String reasonCode) {
        scheduler.execute(() -> {
            try {
                blocking.withDeadlineAfter(5, TimeUnit.SECONDS).requestResync(ResyncRequest.newBuilder()
                        .setClientApplicationKey(config.clientApplicationKey())
                        .setEnvironmentKey(config.environmentKey())
                        .setLastAppliedSnapshotVersion(lastAppliedVersion)
                        .setReasonCode(reasonCode)
                        .build());
            } catch (RuntimeException exception) {
                listener.onDisconnected(exception);
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    private void connect() {
        if (closed.get()) {
            return;
        }
        reconnectScheduled.set(false);
        async.subscribe(SubscribeRequest.newBuilder()
                .setClientApplicationKey(config.clientApplicationKey())
                .setProjectKey(config.projectKey())
                .setEnvironmentKey(config.environmentKey())
                .setLastAppliedSnapshotVersion(lastAppliedVersion.getAsLong())
                .setSupportedSchemaVersion(SnapshotDecoder.SUPPORTED_SCHEMA_VERSION)
                .build(), new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                        reconnectBackoff.reset();
                        listener.onConnected();
                        if (message.hasFullSnapshot()) {
                            listener.onSnapshot(message.getFullSnapshot());
                        } else if (message.hasHeartbeat()) {
                            listener.onHeartbeat(message.getHeartbeat().getCurrentSnapshotVersion());
                        } else if (message.hasResyncRequired()) {
                            listener.onResyncRequired(
                                    message.getResyncRequired().getReasonCode(),
                                    message.getResyncRequired().getCurrentSnapshotVersion());
                        } else if (message.hasCredentialRevoked()) {
                            if (closed.compareAndSet(false, true)) {
                                scheduler.shutdown();
                                channel.shutdown();
                            }
                            listener.onCredentialRevoked();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        listener.onDisconnected(throwable);
                        scheduleReconnect();
                    }

                    @Override
                    public void onCompleted() {
                        listener.onDisconnected(null);
                        scheduleReconnect();
                    }
                });
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        Duration delay = reconnectBackoff.nextDelay();
        scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
