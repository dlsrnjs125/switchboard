package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.ImmutableContext;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcSnapshotTransportIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realGrpcClientSubscribesAppliesAndAcknowledges() throws Exception {
        CountDownLatch acknowledged = new CountDownLatch(1);
        var snapshot = SnapshotTestData.snapshot(7, Instant.now(), true);
        Server server = NettyServerBuilder.forPort(0)
                .addService(new SnapshotDistributionServiceGrpc.SnapshotDistributionServiceImplBase() {
                    @Override
                    public void subscribe(
                            SubscribeRequest request, StreamObserver<ServerMessage> responseObserver) {
                        assertEquals(0, request.getLastAppliedSnapshotVersion());
                        responseObserver.onNext(ServerMessage.newBuilder().setFullSnapshot(snapshot).build());
                    }

                    @Override
                    public void acknowledge(
                            AckRequest request, StreamObserver<AckResponse> responseObserver) {
                        if (request.getSnapshotVersion() == 7
                                && request.getDeliveryId().equals(snapshot.getDeliveryId())
                                && request.getChecksum().equals(snapshot.getChecksum())) {
                            acknowledged.countDown();
                        }
                        responseObserver.onNext(AckResponse.newBuilder().setAccepted(true).build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:" + server.getPort(), "test-credential", "orders", "checkout", "production",
                temporaryDirectory.resolve("lkg.json"), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofMillis(10), Duration.ofSeconds(1), 0, java.time.Clock.systemUTC());
        SwitchboardProvider provider = new SwitchboardProvider(config);

        try {
            provider.initialize(ImmutableContext.EMPTY);

            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
            assertEquals(7, provider.lastAppliedVersion());
            assertTrue(provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY).getValue());
        } finally {
            provider.shutdown();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void retriesTransientAcknowledgementFailureWithTheSameDelivery() throws Exception {
        CountDownLatch acknowledged = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var snapshot = SnapshotTestData.snapshot(8, Instant.now(), true);
        Server server = NettyServerBuilder.forPort(0)
                .addService(new SnapshotDistributionServiceGrpc.SnapshotDistributionServiceImplBase() {
                    @Override
                    public void subscribe(
                            SubscribeRequest request, StreamObserver<ServerMessage> responseObserver) {
                        responseObserver.onNext(ServerMessage.newBuilder().setFullSnapshot(snapshot).build());
                    }

                    @Override
                    public void acknowledge(
                            AckRequest request, StreamObserver<AckResponse> responseObserver) {
                        if (attempts.incrementAndGet() < 3) {
                            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                            return;
                        }
                        assertEquals(snapshot.getDeliveryId(), request.getDeliveryId());
                        assertEquals(8, request.getSnapshotVersion());
                        acknowledged.countDown();
                        responseObserver.onNext(AckResponse.newBuilder().setAccepted(true).build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:" + server.getPort(), "test-credential", "orders", "checkout", "production",
                temporaryDirectory.resolve("retry-lkg.json"), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofMillis(10), Duration.ofSeconds(1), 0, java.time.Clock.systemUTC());
        SwitchboardProvider provider = new SwitchboardProvider(config);

        try {
            provider.initialize(ImmutableContext.EMPTY);

            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(3, attempts.get());
            assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
            assertEquals(8, provider.lastAppliedVersion());
        } finally {
            provider.shutdown();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void doesNotRetryPermanentAcknowledgementFailure() throws Exception {
        CountDownLatch firstAttempt = new CountDownLatch(1);
        CountDownLatch unexpectedRetry = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var snapshot = SnapshotTestData.snapshot(9, Instant.now(), true);
        Server server = NettyServerBuilder.forPort(0)
                .addService(new SnapshotDistributionServiceGrpc.SnapshotDistributionServiceImplBase() {
                    @Override
                    public void subscribe(
                            SubscribeRequest request, StreamObserver<ServerMessage> responseObserver) {
                        responseObserver.onNext(ServerMessage.newBuilder().setFullSnapshot(snapshot).build());
                    }

                    @Override
                    public void acknowledge(
                            AckRequest request, StreamObserver<AckResponse> responseObserver) {
                        if (attempts.incrementAndGet() == 1) {
                            firstAttempt.countDown();
                        } else {
                            unexpectedRetry.countDown();
                        }
                        responseObserver.onError(Status.PERMISSION_DENIED.asRuntimeException());
                    }
                })
                .build()
                .start();
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:" + server.getPort(), "test-credential", "orders", "checkout", "production",
                temporaryDirectory.resolve("permanent-failure-lkg.json"), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofMillis(10), Duration.ofSeconds(1), 0, java.time.Clock.systemUTC());
        SwitchboardProvider provider = new SwitchboardProvider(config);

        try {
            provider.initialize(ImmutableContext.EMPTY);

            assertTrue(firstAttempt.await(5, TimeUnit.SECONDS));
            assertFalse(unexpectedRetry.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, attempts.get());
            assertEquals(9, provider.lastAppliedVersion());
        } finally {
            provider.shutdown();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stalledAcknowledgementDoesNotDelayReconnectScheduler() throws Exception {
        CountDownLatch acknowledgementStarted = new CountDownLatch(1);
        CountDownLatch reconnected = new CountDownLatch(1);
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<StreamObserver<ServerMessage>> firstStream = new AtomicReference<>();
        var snapshot = SnapshotTestData.snapshot(10, Instant.now(), true);
        Server server = NettyServerBuilder.forPort(0)
                .addService(new SnapshotDistributionServiceGrpc.SnapshotDistributionServiceImplBase() {
                    @Override
                    public void subscribe(
                            SubscribeRequest request, StreamObserver<ServerMessage> responseObserver) {
                        if (subscriptions.incrementAndGet() == 1) {
                            firstStream.set(responseObserver);
                            responseObserver.onNext(ServerMessage.newBuilder().setFullSnapshot(snapshot).build());
                        } else {
                            reconnected.countDown();
                        }
                    }

                    @Override
                    public void acknowledge(
                            AckRequest request, StreamObserver<AckResponse> responseObserver) {
                        acknowledgementStarted.countDown();
                        // Deliberately leave the unary RPC open until the transport closes it.
                    }
                })
                .build()
                .start();
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:" + server.getPort(), "test-credential", "orders", "checkout", "production",
                temporaryDirectory.resolve("isolated-executor-lkg.json"), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofMillis(50), Duration.ofMillis(50), 0, java.time.Clock.systemUTC());
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService controlRpcExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<GrpcSnapshotTransport> transportReference = new AtomicReference<>();
        AtomicLong appliedVersion = new AtomicLong();
        GrpcSnapshotTransport transport = new GrpcSnapshotTransport(
                config, channel, reconnectScheduler, controlRpcExecutor, () -> 0.5);
        transportReference.set(transport);

        try {
            transport.start(appliedVersion::get, new SnapshotTransport.Listener() {
                @Override
                public void onConnected() {
                }

                @Override
                public void onSnapshot(io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot value) {
                    appliedVersion.set(value.getSnapshotVersion());
                    transportReference.get().acknowledge(
                            value.getDeliveryId(), value.getSnapshotVersion(), value.getChecksum());
                }

                @Override
                public void onHeartbeat(long currentSnapshotVersion) {
                }

                @Override
                public void onResyncRequired(String reasonCode, long currentSnapshotVersion) {
                }

                @Override
                public void onCredentialRevoked() {
                }

                @Override
                public void onDisconnected(Throwable cause) {
                }
            });

            assertTrue(acknowledgementStarted.await(5, TimeUnit.SECONDS));
            firstStream.get().onError(Status.UNAVAILABLE.asRuntimeException());
            assertTrue(reconnected.await(1, TimeUnit.SECONDS),
                    "reconnect must run while the control RPC executor is blocked");
        } finally {
            transport.close();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
