package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.ImmutableContext;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
}
