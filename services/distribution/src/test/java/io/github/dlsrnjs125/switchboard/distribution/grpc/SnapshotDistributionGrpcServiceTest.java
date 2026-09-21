package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SnapshotDistributionGrpcServiceTest {
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
