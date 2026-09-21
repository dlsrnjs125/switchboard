package io.github.dlsrnjs125.switchboard.distribution.grpc;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.NackRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.NackResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ResyncRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ResyncResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotIntegrityException;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SnapshotDistributionGrpcService
        extends SnapshotDistributionServiceGrpc.SnapshotDistributionServiceImplBase {
    private final SnapshotCoordinator coordinator;
    private final SessionRegistry sessions;
    private final Clock clock;

    public SnapshotDistributionGrpcService(
            SnapshotCoordinator coordinator,
            SessionRegistry sessions,
            Clock clock) {
        this.coordinator = coordinator;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public void subscribe(SubscribeRequest request, StreamObserver<ServerMessage> responseObserver) {
        CredentialPrincipal principal = principal();
        if (!matches(principal, request.getClientApplicationKey(), request.getProjectKey(),
                request.getEnvironmentKey())) {
            responseObserver.onError(Status.PERMISSION_DENIED
                    .withDescription("scope not authorized").asRuntimeException());
            return;
        }
        if (request.getSupportedSchemaVersion() != DistributionSnapshotValidator.SUPPORTED_SCHEMA_VERSION) {
            responseObserver.onNext(GrpcMessages.resyncRequired("UNSUPPORTED_SCHEMA_VERSION", 0));
            responseObserver.onCompleted();
            return;
        }

        @SuppressWarnings("unchecked")
        ServerCallStreamObserver<ServerMessage> serverObserver =
                (ServerCallStreamObserver<ServerMessage>) responseObserver;
        ClientSession session;
        try {
            session = sessions.register(principal, serverObserver, request.getLastAppliedSnapshotVersion());
        } catch (io.grpc.StatusRuntimeException exception) {
            responseObserver.onError(exception);
            return;
        }
        try {
            Optional<SnapshotArtifact> current = coordinator.current(principal.scope());
            if (current.isEmpty()) {
                session.heartbeat(clock.millis(), 0);
                return;
            }
            SnapshotArtifact snapshot = current.orElseThrow();
            if (request.getLastAppliedSnapshotVersion() > snapshot.snapshotVersion()) {
                session.resyncRequired("CLIENT_VERSION_AHEAD", snapshot.snapshotVersion());
            } else if (request.getLastAppliedSnapshotVersion() == snapshot.snapshotVersion()) {
                session.heartbeat(clock.millis(), snapshot.snapshotVersion());
            } else {
                session.offerSnapshot(snapshot);
            }
        } catch (SnapshotIntegrityException exception) {
            sessions.unregister(session);
            responseObserver.onNext(GrpcMessages.resyncRequired("SNAPSHOT_INTEGRITY_FAILURE", 0));
            responseObserver.onCompleted();
        } catch (RuntimeException exception) {
            sessions.unregister(session);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("authoritative snapshot unavailable").asRuntimeException());
        }
    }

    @Override
    public void acknowledge(AckRequest request, StreamObserver<AckResponse> responseObserver) {
        CredentialPrincipal principal = principal();
        boolean scoped = matches(principal, request.getClientApplicationKey(), null, request.getEnvironmentKey());
        boolean accepted = scoped && coordinator.cached(principal.scope())
                .map(snapshot -> snapshot.snapshotVersion() == request.getSnapshotVersion()
                        && snapshot.checksum().equals(request.getChecksum()))
                .orElse(false);
        responseObserver.onNext(AckResponse.newBuilder().setAccepted(accepted).build());
        responseObserver.onCompleted();
    }

    @Override
    public void reject(NackRequest request, StreamObserver<NackResponse> responseObserver) {
        CredentialPrincipal principal = principal();
        boolean accepted = matches(principal, request.getClientApplicationKey(), null, request.getEnvironmentKey());
        if (accepted) {
            try {
                coordinator.current(principal.scope());
                sessions.requestResync(principal);
            } catch (RuntimeException exception) {
                accepted = false;
            }
        }
        responseObserver.onNext(NackResponse.newBuilder().setAccepted(accepted).build());
        responseObserver.onCompleted();
    }

    @Override
    public void requestResync(ResyncRequest request, StreamObserver<ResyncResponse> responseObserver) {
        CredentialPrincipal principal = principal();
        boolean accepted = matches(principal, request.getClientApplicationKey(), null, request.getEnvironmentKey());
        if (accepted) {
            try {
                coordinator.current(principal.scope());
                sessions.requestResync(principal);
            } catch (RuntimeException exception) {
                accepted = false;
            }
        }
        responseObserver.onNext(ResyncResponse.newBuilder().setAccepted(accepted).build());
        responseObserver.onCompleted();
    }

    private CredentialPrincipal principal() {
        CredentialPrincipal principal = CredentialServerInterceptor.PRINCIPAL.get();
        if (principal == null) {
            throw Status.UNAUTHENTICATED.asRuntimeException();
        }
        return principal;
    }

    private boolean matches(
            CredentialPrincipal principal,
            String clientApplicationKey,
            String projectKey,
            String environmentKey) {
        return principal.clientApplicationKey().equals(clientApplicationKey)
                && (projectKey == null || principal.scope().projectKey().equals(projectKey))
                && principal.scope().environmentKey().equals(environmentKey);
    }
}
