package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.observability.DistributionTelemetry;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SnapshotDistributionGrpcServiceTest {
    @Test
    void bootstrapFailureTerminatesPendingSessionAndClearsTelemetry() throws Exception {
        EnvironmentScope scope = scope();
        CredentialPrincipal principal = new CredentialPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "orders", scope);
        DistributionRepository repository = mock(DistributionRepository.class);
        SnapshotCache cache = new SnapshotCache();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry = new DistributionTelemetry(meters, ObservationRegistry.NOOP);
        SessionRegistry sessions = new SessionRegistry(
                repository, cache, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 10, telemetry);
        SnapshotCoordinator coordinator = mock(SnapshotCoordinator.class);
        SnapshotArtifact overlapping = snapshot(scope, 11);
        when(coordinator.current(scope)).thenAnswer(invocation -> {
            sessions.onSnapshotApplied(overlapping);
            throw new IllegalStateException("authoritative lookup failed");
        });
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(
                coordinator, sessions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), telemetry);
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> observer = mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(false);
        doAnswer(invocation -> null).when(observer).setOnReadyHandler(any());
        doAnswer(invocation -> null).when(observer).setOnCancelHandler(any());

        Context.current().withValue(CredentialServerInterceptor.PRINCIPAL, principal).call(() -> {
            service.subscribe(SubscribeRequest.newBuilder()
                    .setClientApplicationKey("orders")
                    .setProjectKey("checkout")
                    .setEnvironmentKey("production")
                    .setSupportedSchemaVersion(1)
                    .build(), observer);
            return null;
        });

        verify(observer).onError(any(io.grpc.StatusRuntimeException.class));
        assertEquals(0, sessions.size());
        assertEquals(0.0, meters.get("switchboard.distribution.sessions.connected").gauge().value());
        assertEquals(0.0, meters.get("switchboard.distribution.snapshot.pending").gauge().value());
        assertEquals(0.0,
                meters.get("switchboard.distribution.snapshot.pending.bytes").gauge().value());
    }

    @Test
    void subscriberConvergesWhenNewSnapshotIsBroadcastDuringBootstrap() throws Exception {
        EnvironmentScope scope = scope();
        CredentialPrincipal principal = new CredentialPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "orders", scope);
        DistributionRepository repository = mock(DistributionRepository.class);
        SnapshotCache cache = new SnapshotCache();
        SessionRegistry sessions = new SessionRegistry(
                repository, cache, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        SnapshotCoordinator coordinator = mock(SnapshotCoordinator.class);
        SnapshotArtifact versionTen = snapshot(scope, 10);
        SnapshotArtifact versionEleven = snapshot(scope, 11);
        when(coordinator.current(scope)).thenAnswer(invocation -> {
            sessions.onSnapshotApplied(versionEleven);
            return Optional.of(versionTen);
        });
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(
                coordinator, sessions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> observer = mock(ServerCallStreamObserver.class);
        when(observer.isReady()).thenReturn(true);
        doAnswer(invocation -> null).when(observer).setOnReadyHandler(any());
        doAnswer(invocation -> null).when(observer).setOnCancelHandler(any());

        Context.current().withValue(CredentialServerInterceptor.PRINCIPAL, principal).call(() -> {
            service.subscribe(SubscribeRequest.newBuilder()
                    .setClientApplicationKey("orders")
                    .setProjectKey("checkout")
                    .setEnvironmentKey("production")
                    .setSupportedSchemaVersion(1)
                    .build(), observer);
            return null;
        });

        ArgumentCaptor<ServerMessage> delivered = ArgumentCaptor.forClass(ServerMessage.class);
        verify(observer).onNext(delivered.capture());
        assertEquals(11, delivered.getValue().getFullSnapshot().getSnapshotVersion());
    }

    @Test
    void rejectedReconnectIsAdmissionControlledBeforeAuthoritativeSnapshotLoad() throws Exception {
        EnvironmentScope scope = scope();
        CredentialPrincipal principal = new CredentialPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "orders", scope);
        DistributionRepository repository = mock(DistributionRepository.class);
        SessionRegistry sessions = new SessionRegistry(
                repository, new SnapshotCache(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1);
        SnapshotCoordinator coordinator = mock(SnapshotCoordinator.class);
        when(coordinator.current(scope)).thenReturn(Optional.empty());
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(
                coordinator, sessions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> admitted = mock(ServerCallStreamObserver.class);
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> rejected = mock(ServerCallStreamObserver.class);
        for (ServerCallStreamObserver<ServerMessage> observer : java.util.List.of(admitted, rejected)) {
            when(observer.isReady()).thenReturn(true);
            doAnswer(invocation -> null).when(observer).setOnReadyHandler(any());
            doAnswer(invocation -> null).when(observer).setOnCancelHandler(any());
        }
        SubscribeRequest request = SubscribeRequest.newBuilder()
                .setClientApplicationKey("orders")
                .setProjectKey("checkout")
                .setEnvironmentKey("production")
                .setSupportedSchemaVersion(1)
                .build();

        Context.current().withValue(CredentialServerInterceptor.PRINCIPAL, principal).call(() -> {
            service.subscribe(request, admitted);
            service.subscribe(request, rejected);
            return null;
        });

        verify(coordinator, times(1)).current(scope);
        verify(rejected).onError(any(io.grpc.StatusRuntimeException.class));
        assertEquals(1, sessions.size());
    }

    private SnapshotArtifact snapshot(EnvironmentScope scope, long version) {
        return new SnapshotArtifact(
                UUID.randomUUID(), scope, version, 1, "a".repeat(64),
                new byte[] {(byte) version}, JsonNodeFactory.instance.objectNode(), Instant.EPOCH);
    }

    private EnvironmentScope scope() {
        return new EnvironmentScope(
                UUID.fromString("018f1000-0000-7000-8000-000000000001"),
                UUID.fromString("018f1000-0000-7000-8000-000000000002"),
                UUID.fromString("018f1000-0000-7000-8000-000000000003"),
                "acme", "checkout", "production");
    }
}
