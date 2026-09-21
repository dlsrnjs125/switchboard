package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.ImmutableContext;
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
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcDistributionIntegrationTest extends DistributionPostgresSupport {
    @TempDir
    Path temporaryDirectory;

    private GrpcServerLifecycle server;
    private ManagedChannel channel;
    private SessionRegistry sessions;
    private SwitchboardProvider provider;

    @AfterEach
    void close() {
        if (provider != null) {
            provider.shutdown();
        }
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

    @Test
    void rotatedCredentialRestoresOnlyTheOriginalApplicationScope() {
        SnapshotFixture snapshot = insertSnapshot(1);
        startServer(0, 100);
        revokeCredential();
        UUID rotatedId = UUID.randomUUID();
        String rotatedSecret = "rotated-test-secret-material-with-enough-entropy";
        jdbc.update("""
                INSERT INTO service_credentials (
                    id, tenant_id, project_id, client_application_id, secret_hash,
                    secret_prefix, status, created_at
                ) VALUES (?, ?, ?, ?, ?, 'rotated', 'ACTIVE', ?)
                """, rotatedId, tenantId, projectId, applicationId,
                passwordEncoder.encode(rotatedSecret), java.sql.Timestamp.from(clock.instant()));

        ManagedChannel rotatedChannel = ManagedChannelBuilder
                .forAddress("localhost", server.port()).usePlaintext().build();
        try {
            SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub rotated =
                    authenticated(rotatedChannel, rotatedId + "." + rotatedSecret);
            ServerMessage message = rotated.subscribe(subscribe("checkout", 0)).next();

            assertTrue(message.hasFullSnapshot());
            assertEquals(snapshot.checksum(), message.getFullSnapshot().getChecksum());
            StatusRuntimeException wrongScope = assertThrows(
                    StatusRuntimeException.class,
                    () -> rotated.subscribe(subscribe("other-project", 0)).next());
            assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());
        } finally {
            rotatedChannel.shutdownNow();
        }

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM service_credentials WHERE secret_hash = ?",
                Integer.class,
                rotatedSecret));
    }

    @Test
    void concurrentSessionAdmissionIsBoundedBeforeSnapshotLoading() throws Exception {
        insertSnapshot(1);
        Stubs stubs = start(1);
        CountDownLatch firstConnected = new CountDownLatch(1);
        stubs.async.subscribe(subscribe("checkout", 0), new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage value) {
                firstConnected.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                // The first admitted stream remains open until test cleanup.
            }

            @Override
            public void onCompleted() {
                // The first admitted stream remains open until test cleanup.
            }
        });
        assertTrue(firstConnected.await(5, TimeUnit.SECONDS));

        StatusRuntimeException rejected = assertThrows(
                StatusRuntimeException.class,
                () -> stubs.blocking.subscribe(subscribe("checkout", 0)).next());

        assertEquals(Status.Code.RESOURCE_EXHAUSTED, rejected.getStatus().getCode());
        assertEquals(1, sessions.size());
    }

    @Test
    void providerKeepsLocalEvaluationDuringDistributionRestartAndConvergesAgain() throws Exception {
        insertSnapshot(3);
        startServer(0, 100);
        int restartPort = server.port();
        provider = new SwitchboardProvider(new SwitchboardProviderConfig(
                "localhost:" + restartPort,
                bearer(),
                "orders",
                "checkout",
                "production",
                temporaryDirectory.resolve("lkg.json"),
                Duration.ofSeconds(30),
                Duration.ofDays(7),
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                0,
                clock));
        provider.initialize(ImmutableContext.EMPTY);
        awaitState(SwitchboardProviderState.READY);
        assertTrue(provider.getBooleanEvaluation(
                "checkout-v2", false, ImmutableContext.EMPTY).getValue());

        server.stop();
        server = null;
        awaitState(SwitchboardProviderState.READY_STALE);
        for (int evaluation = 0; evaluation < 1_000; evaluation++) {
            assertTrue(provider.getBooleanEvaluation(
                    "checkout-v2", false, ImmutableContext.EMPTY).getValue());
        }

        startServer(restartPort, 100);
        awaitState(SwitchboardProviderState.READY);
        assertEquals(3, provider.lastAppliedVersion());
        assertTrue(provider.getBooleanEvaluation(
                "checkout-v2", false, ImmutableContext.EMPTY).getValue());
    }

    private Stubs start() {
        return start(10_000);
    }

    private Stubs start(int maximumSessions) {
        startServer(0, maximumSessions);
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
        return new Stubs(
                authenticated(channel, bearer()),
                authenticatedAsync(channel, bearer()));
    }

    private void startServer(int port, int maximumSessions) {
        SnapshotCache cache = new SnapshotCache();
        sessions = new SessionRegistry(repository, cache, clock, maximumSessions);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions));
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        server = new GrpcServerLifecycle(service, new CredentialServerInterceptor(repository), sessions, port);
        server.start();
    }

    private void awaitState(SwitchboardProviderState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (provider.switchboardState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertEquals(expected, provider.switchboardState());
    }

    private SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub authenticated(
            ManagedChannel targetChannel, String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return SnapshotDistributionServiceGrpc.newBlockingStub(targetChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub authenticatedAsync(
            ManagedChannel targetChannel, String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return SnapshotDistributionServiceGrpc.newStub(targetChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
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
