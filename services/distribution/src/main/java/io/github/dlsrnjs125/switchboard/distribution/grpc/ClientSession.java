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
    private final long clientSnapshotVersion;
    private final SnapshotSentListener onSnapshotSent;
    private final BackpressureListener backpressure;
    private final AtomicReference<ServerMessage> pendingSnapshot = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long highestOfferedSnapshotVersion = -1;

    ClientSession(
            UUID id,
            CredentialPrincipal principal,
            ServerCallStreamObserver<ServerMessage> observer,
            long clientSnapshotVersion,
            Runnable onClose) {
        this(id, principal, observer, clientSnapshotVersion, onClose,
                (sessionId, deliveryId, version) -> { }, BackpressureListener.noop());
    }

    ClientSession(
            UUID id,
            CredentialPrincipal principal,
            ServerCallStreamObserver<ServerMessage> observer,
            long clientSnapshotVersion,
            Runnable onClose,
            SnapshotSentListener onSnapshotSent) {
        this(id, principal, observer, clientSnapshotVersion, onClose, onSnapshotSent,
                BackpressureListener.noop());
    }

    ClientSession(
            UUID id,
            CredentialPrincipal principal,
            ServerCallStreamObserver<ServerMessage> observer,
            long clientSnapshotVersion,
            Runnable onClose,
            SnapshotSentListener onSnapshotSent,
            BackpressureListener backpressure) {
        this.id = id;
        this.principal = principal;
        this.observer = observer;
        this.clientSnapshotVersion = clientSnapshotVersion;
        this.onSnapshotSent = onSnapshotSent;
        this.backpressure = backpressure;
        observer.setOnReadyHandler(this::drain);
        observer.setOnCancelHandler(() -> cancel(onClose));
    }

    UUID id() {
        return id;
    }

    CredentialPrincipal principal() {
        return principal;
    }

    synchronized void offerSnapshot(SnapshotArtifact snapshot) {
        if (closed.get()
                || snapshot.snapshotVersion() <= clientSnapshotVersion
                || snapshot.snapshotVersion() <= highestOfferedSnapshotVersion) {
            return;
        }
        highestOfferedSnapshotVersion = snapshot.snapshotVersion();
        ServerMessage replacement = GrpcMessages.snapshot(snapshot);
        ServerMessage previous = pendingSnapshot.getAndSet(replacement);
        if (previous == null) {
            backpressure.pendingChanged(1, replacement.getSerializedSize());
        } else {
            backpressure.pendingChanged(
                    0, replacement.getSerializedSize() - previous.getSerializedSize());
            backpressure.coalesced();
        }
        drain();
    }

    synchronized void heartbeat(long nowMillis, long currentVersion) {
        if (!closed.get()
                && currentVersion >= highestOfferedSnapshotVersion
                && observer.isReady()
                && pendingSnapshot.get() == null) {
            observer.onNext(GrpcMessages.heartbeat(nowMillis, currentVersion));
        }
    }

    synchronized void resyncRequired(String reason, long currentVersion) {
        if (!closed.get()
                && currentVersion >= highestOfferedSnapshotVersion
                && observer.isReady()) {
            observer.onNext(GrpcMessages.resyncRequired(reason, currentVersion));
        }
    }

    synchronized void revoke() {
        if (terminate()) {
            if (observer.isReady()) {
                observer.onNext(GrpcMessages.credentialRevoked(principal.credentialId().toString()));
            }
            observer.onError(Status.PERMISSION_DENIED
                    .withDescription("service credential revoked")
                    .asRuntimeException());
        }
    }

    synchronized void close() {
        if (terminate()) {
            observer.onCompleted();
        }
    }

    synchronized boolean terminate() {
        if (closed.compareAndSet(false, true)) {
            clearPending();
            return true;
        }
        return false;
    }

    private void cancel(Runnable onClose) {
        if (terminate()) {
            onClose.run();
        }
    }

    private synchronized void drain() {
        if (closed.get() || !observer.isReady()) {
            return;
        }
        ServerMessage next = pendingSnapshot.getAndSet(null);
        if (next != null) {
            backpressure.pendingChanged(-1, -next.getSerializedSize());
            UUID deliveryId = UUID.randomUUID();
            ServerMessage delivered = next.toBuilder()
                    .setFullSnapshot(next.getFullSnapshot().toBuilder()
                            .setDeliveryId(deliveryId.toString()))
                    .build();
            observer.onNext(delivered);
            onSnapshotSent.sent(id, deliveryId, delivered.getFullSnapshot().getSnapshotVersion());
        }
    }

    private void clearPending() {
        ServerMessage discarded = pendingSnapshot.getAndSet(null);
        if (discarded != null) {
            backpressure.pendingChanged(-1, -discarded.getSerializedSize());
        }
    }

    @FunctionalInterface
    interface SnapshotSentListener {
        void sent(UUID sessionId, UUID deliveryId, long snapshotVersion);
    }

    interface BackpressureListener {
        void pendingChanged(int countDelta, long byteDelta);

        void coalesced();

        static BackpressureListener noop() {
            return new BackpressureListener() {
                @Override
                public void pendingChanged(int countDelta, long byteDelta) {
                }

                @Override
                public void coalesced() {
                }
            };
        }
    }
}
