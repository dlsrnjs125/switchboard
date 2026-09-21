package io.github.dlsrnjs125.switchboard.distribution.grpc;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.observability.DistributionTelemetry;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotUpdateListener;
import io.grpc.stub.ServerCallStreamObserver;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionRegistry implements SnapshotUpdateListener {
    private final ConcurrentHashMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private final DistributionRepository repository;
    private final SnapshotCache cache;
    private final Clock clock;
    private final int maximumSessions;
    private final DistributionTelemetry telemetry;

    @Autowired
    public SessionRegistry(
            DistributionRepository repository,
            SnapshotCache cache,
            Clock clock,
            @Value("${switchboard.distribution.maximum-sessions:10000}") int maximumSessions,
            DistributionTelemetry telemetry) {
        if (maximumSessions < 1) {
            throw new IllegalArgumentException("switchboard.distribution.maximum-sessions must be positive");
        }
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
        this.maximumSessions = maximumSessions;
        this.telemetry = telemetry;
    }

    public SessionRegistry(DistributionRepository repository, SnapshotCache cache, Clock clock) {
        this(repository, cache, clock, 10_000, DistributionTelemetry.noop());
    }

    public SessionRegistry(
            DistributionRepository repository, SnapshotCache cache, Clock clock, int maximumSessions) {
        this(repository, cache, clock, maximumSessions, DistributionTelemetry.noop());
    }

    synchronized ClientSession register(
            CredentialPrincipal principal,
            ServerCallStreamObserver<ServerMessage> observer,
            long clientSnapshotVersion) {
        if (sessions.size() >= maximumSessions) {
            telemetry.admissionRejected();
            throw io.grpc.Status.RESOURCE_EXHAUSTED
                    .withDescription("distribution session capacity reached")
                    .asRuntimeException();
        }
        UUID sessionId = UUID.randomUUID();
        ClientSession session = new ClientSession(sessionId, principal, observer, clientSnapshotVersion, () -> {
            if (sessions.remove(sessionId) != null) {
                telemetry.sessionUnregistered(sessionId);
            }
        }, telemetry::snapshotSent);
        sessions.put(sessionId, session);
        telemetry.sessionRegistered();
        return session;
    }

    synchronized void unregister(ClientSession session) {
        if (sessions.remove(session.id(), session)) {
            telemetry.sessionUnregistered(session.id());
        }
    }

    void requestResync(CredentialPrincipal principal) {
        cache.get(principal.scope()).ifPresent(snapshot -> sessions.values().stream()
                .filter(session -> session.principal().credentialId().equals(principal.credentialId()))
                .forEach(session -> session.offerSnapshot(snapshot)));
    }

    @Override
    public void onSnapshotApplied(SnapshotArtifact snapshot) {
        telemetry.grpcEvent("snapshot_send", "attempt", "broadcast", snapshot.snapshotVersion());
        sessions.values().stream()
                .filter(session -> session.principal().scope().equals(snapshot.scope()))
                .forEach(session -> session.offerSnapshot(snapshot));
    }

    @Scheduled(fixedDelayString = "${switchboard.distribution.heartbeat-interval:PT10S}")
    void heartbeat() {
        long now = clock.millis();
        sessions.values().forEach(session -> {
            EnvironmentScope scope = session.principal().scope();
            long version = cache.get(scope).map(SnapshotArtifact::snapshotVersion).orElse(0L);
            session.heartbeat(now, version);
        });
    }

    @Scheduled(fixedDelayString = "${switchboard.distribution.credential-revalidation-interval:PT5S}")
    void enforceCredentialRevocation() {
        sessions.values().forEach(session -> {
            if (!repository.isCredentialActive(session.principal().credentialId())) {
                if (sessions.remove(session.id()) != null) {
                    telemetry.sessionUnregistered(session.id());
                }
                telemetry.credentialRevoked();
                session.revoke();
            }
        });
    }

    void closeAll() {
        var closedSessions = java.util.List.copyOf(sessions.values());
        closedSessions.forEach(session -> {
            session.close();
            if (sessions.remove(session.id(), session)) {
                telemetry.sessionUnregistered(session.id());
            }
        });
    }

    int size() {
        return sessions.size();
    }
}
