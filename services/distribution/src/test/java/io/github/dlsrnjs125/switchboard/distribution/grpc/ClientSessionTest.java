package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClientSessionTest {
    @Test
    void slowClientKeepsOnlyLatestFullSnapshot() {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> observer = mock(ServerCallStreamObserver.class);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicReference<Runnable> onReady = new AtomicReference<>();
        when(observer.isReady()).thenAnswer(ignored -> ready.get());
        doAnswer(invocation -> {
            onReady.set(invocation.getArgument(0));
            return null;
        }).when(observer).setOnReadyHandler(any());
        ClientSession session = new ClientSession(
                UUID.randomUUID(), principal(), observer, 0, () -> { });

        session.offerSnapshot(snapshot(1));
        session.offerSnapshot(snapshot(2));
        session.offerSnapshot(snapshot(3));
        verify(observer, never()).onNext(any());

        ready.set(true);
        onReady.get().run();

        ArgumentCaptor<ServerMessage> delivered = ArgumentCaptor.forClass(ServerMessage.class);
        verify(observer).onNext(delivered.capture());
        assertEquals(3, delivered.getValue().getFullSnapshot().getSnapshotVersion());
    }

    @Test
    void recordsSendOnlyWhenFullSnapshotIsActuallyEmitted() {
        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> observer = mock(ServerCallStreamObserver.class);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicReference<Runnable> onReady = new AtomicReference<>();
        AtomicReference<UUID> sentSession = new AtomicReference<>();
        AtomicReference<UUID> sentDelivery = new AtomicReference<>();
        AtomicLong sentVersion = new AtomicLong();
        CredentialPrincipal principal = principal();
        UUID sessionId = UUID.randomUUID();
        when(observer.isReady()).thenAnswer(ignored -> ready.get());
        doAnswer(invocation -> {
            onReady.set(invocation.getArgument(0));
            return null;
        }).when(observer).setOnReadyHandler(any());
        ClientSession session = new ClientSession(
                sessionId, principal, observer, 0, () -> { }, (actualSessionId, deliveryId, version) -> {
                    sentSession.set(actualSessionId);
                    sentDelivery.set(deliveryId);
                    sentVersion.set(version);
                });

        session.offerSnapshot(snapshot(4));
        assertEquals(0, sentVersion.get());

        ready.set(true);
        onReady.get().run();

        ArgumentCaptor<ServerMessage> delivered = ArgumentCaptor.forClass(ServerMessage.class);
        verify(observer).onNext(delivered.capture());
        assertEquals(sessionId, sentSession.get());
        assertEquals(sentDelivery.get().toString(), delivered.getValue().getFullSnapshot().getDeliveryId());
        assertEquals(4, sentVersion.get());
    }

    private CredentialPrincipal principal() {
        return new CredentialPrincipal(UUID.randomUUID(), UUID.randomUUID(), "orders", scope());
    }

    private SnapshotArtifact snapshot(long version) {
        return new SnapshotArtifact(
                UUID.randomUUID(), scope(), version, 1, "a".repeat(64),
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
