package io.github.dlsrnjs125.switchboard.distribution.grpc;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ClientSession {
    private final UUID id;
    private final CredentialPrincipal principal;
    private final ServerCallStreamObserver<ServerMessage> observer;
    private final AtomicReference<ServerMessage> pendingSnapshot = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    ClientSession(
            UUID id,
            CredentialPrincipal principal,
            ServerCallStreamObserver<ServerMessage> observer,
            Runnable onClose) {
        this.id = id;
        this.principal = principal;
        this.observer = observer;
        observer.setOnReadyHandler(this::drain);
        observer.setOnCancelHandler(() -> {
            closed.set(true);
            onClose.run();
        });
    }

    UUID id() {
        return id;
    }

    CredentialPrincipal principal() {
        return principal;
    }

    void offerSnapshot(SnapshotArtifact snapshot) {
        if (!closed.get()) {
            pendingSnapshot.set(GrpcMessages.snapshot(snapshot));
            drain();
        }
    }

    synchronized void heartbeat(long nowMillis, long currentVersion) {
        if (!closed.get() && observer.isReady() && pendingSnapshot.get() == null) {
            observer.onNext(GrpcMessages.heartbeat(nowMillis, currentVersion));
        }
    }

    synchronized void resyncRequired(String reason, long currentVersion) {
        if (!closed.get() && observer.isReady()) {
            observer.onNext(GrpcMessages.resyncRequired(reason, currentVersion));
        }
    }

    synchronized void revoke() {
        if (closed.compareAndSet(false, true)) {
            if (observer.isReady()) {
                observer.onNext(GrpcMessages.credentialRevoked(principal.credentialId().toString()));
            }
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription("service credential revoked")
                    .asRuntimeException());
        }
    }

    synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            observer.onCompleted();
        }
    }

    private synchronized void drain() {
        if (closed.get() || !observer.isReady()) {
            return;
        }
        ServerMessage next = pendingSnapshot.getAndSet(null);
        if (next != null) {
            observer.onNext(next);
        }
    }
}
