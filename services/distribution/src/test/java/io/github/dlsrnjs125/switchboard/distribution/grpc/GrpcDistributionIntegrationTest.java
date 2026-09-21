package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcDistributionIntegrationTest extends DistributionPostgresSupport {
    private GrpcServerLifecycle server;
    private ManagedChannel channel;
    private SessionRegistry sessions;

    @AfterEach
    void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void authenticatedClientReceivesFullSnapshotAndCanAcknowledge() {
        SnapshotFixture snapshot = insertSnapshot(3);
        SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub stub = start().blocking;

        ServerMessage message = stub.subscribe(subscribe("checkout", 0)).next();

        assertTrue(message.hasFullSnapshot());
        assertEquals(3, message.getFullSnapshot().getSnapshotVersion());
        assertEquals(snapshot.checksum(), message.getFullSnapshot().getChecksum());
        assertTrue(stub.acknowledge(AckRequest.newBuilder()
                        .setClientApplicationKey("orders")
                        .setEnvironmentKey("production")
                        .setSnapshotVersion(3)
                        .setChecksum(snapshot.checksum())
                        .build())
                .getAccepted());
    }

    @Test
    void reconnectAtCurrentVersionReceivesHeartbeatAndClientAheadRequiresResync() {
        insertSnapshot(3);
        SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub stub = start().blocking;

        ServerMessage current = stub.subscribe(subscribe("checkout", 3)).next();
        ServerMessage ahead = stub.subscribe(subscribe("checkout", 4)).next();

        assertTrue(current.hasHeartbeat());
        assertEquals(3, current.getHeartbeat().getCurrentSnapshotVersion());
        assertTrue(ahead.hasResyncRequired());
        assertEquals("CLIENT_VERSION_AHEAD", ahead.getResyncRequired().getReasonCode());
        assertEquals(3, ahead.getResyncRequired().getCurrentSnapshotVersion());
    }

    @Test
    void wrongScopeAndRevokedCredentialsAreRejectedWithoutSnapshotDisclosure() {
        insertSnapshot(1);
        SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub stub = start().blocking;

        StatusRuntimeException wrongScope = assertThrows(
                StatusRuntimeException.class,
                () -> stub.subscribe(subscribe("other-project", 0)).next());
        assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());

        revokeCredential();
        StatusRuntimeException revoked = assertThrows(
                StatusRuntimeException.class,
                () -> stub.subscribe(subscribe("checkout", 0)).next());
        assertEquals(Status.Code.UNAUTHENTICATED, revoked.getStatus().getCode());
    }

    @Test
    void establishedStreamClosesWithinRevalidationWhenCredentialIsRevoked() throws Exception {
        insertSnapshot(1);
        Stubs stubs = start();
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        stubs.async.subscribe(subscribe("checkout", 0), new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
                received.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
                closed.countDown();
            }

            @Override
            public void onCompleted() {
                closed.countDown();
            }
        });
        assertTrue(received.await(5, TimeUnit.SECONDS));

        revokeCredential();
        sessions.enforceCredentialRevocation();

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(failure.get()).getCode());
        assertEquals(0, sessions.size());
    }

    private Stubs start() {
        SnapshotCache cache = new SnapshotCache();
        sessions = new SessionRegistry(repository, cache, clock);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions));
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        server = new GrpcServerLifecycle(service, new CredentialServerInterceptor(repository), sessions, 0);
        server.start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer());
        var interceptor = MetadataUtils.newAttachHeadersInterceptor(headers);
        return new Stubs(
                SnapshotDistributionServiceGrpc.newBlockingStub(channel).withInterceptors(interceptor),
                SnapshotDistributionServiceGrpc.newStub(channel).withInterceptors(interceptor));
    }

    private SubscribeRequest subscribe(String projectKey, long lastVersion) {
        return SubscribeRequest.newBuilder()
                .setClientApplicationKey("orders")
                .setProjectKey(projectKey)
                .setEnvironmentKey("production")
                .setLastAppliedSnapshotVersion(lastVersion)
                .setSupportedSchemaVersion(1)
                .build();
    }

    private record Stubs(
            SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub blocking,
            SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub async) {
    }
}
