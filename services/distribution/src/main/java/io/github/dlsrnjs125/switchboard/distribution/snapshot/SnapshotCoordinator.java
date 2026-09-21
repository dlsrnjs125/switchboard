package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.observability.DistributionTelemetry;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache.ApplyOutcome;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache.CacheUpdate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class SnapshotCoordinator {
    private final DistributionRepository repository;
    private final DistributionSnapshotValidator validator;
    private final SnapshotCache cache;
    private final ProcessedEventWindow processedEvents;
    private final List<SnapshotUpdateListener> listeners;
    private final DistributionTelemetry telemetry;

    @Autowired
    public SnapshotCoordinator(
            DistributionRepository repository,
            DistributionSnapshotValidator validator,
            SnapshotCache cache,
            ProcessedEventWindow processedEvents,
            List<SnapshotUpdateListener> listeners,
            DistributionTelemetry telemetry) {
        this.repository = repository;
        this.validator = validator;
        this.cache = cache;
        this.processedEvents = processedEvents;
        this.listeners = listeners;
        this.telemetry = telemetry;
    }

    public SnapshotCoordinator(
            DistributionRepository repository,
            DistributionSnapshotValidator validator,
            SnapshotCache cache,
            ProcessedEventWindow processedEvents,
            List<SnapshotUpdateListener> listeners) {
        this(repository, validator, cache, processedEvents, listeners, DistributionTelemetry.noop());
    }

    public Optional<SnapshotArtifact> current(EnvironmentScope scope) {
        Optional<SnapshotArtifact> authoritative = repository.loadCurrentSnapshot(scope);
        if (authoritative.isEmpty()) {
            return cache.get(scope);
        }
        return Optional.of(applyValidated(authoritative.orElseThrow()).active());
    }

    public ReconcileOutcome reconcile(SnapshotNotification notification) {
        return telemetry.observeReconcile(notification, () -> reconcileAuthoritative(notification));
    }

    private ReconcileOutcome reconcileAuthoritative(SnapshotNotification notification) {
        if (processedEvents.contains(notification.eventId())) {
            return ReconcileOutcome.DUPLICATE_EVENT;
        }
        SnapshotArtifact authoritative = repository.loadCurrentSnapshot(
                        notification.tenantKey(), notification.projectKey(), notification.environmentKey())
                .orElseThrow(() -> new SnapshotIntegrityException("notification scope has no current snapshot"));
        if (notification.snapshotVersion() > authoritative.snapshotVersion()) {
            throw new SnapshotIntegrityException("notification is ahead of authoritative PostgreSQL state");
        }
        if (notification.snapshotVersion() == authoritative.snapshotVersion()
                && !notification.checksum().equals(authoritative.checksum())) {
            throw new SnapshotIntegrityException("same-version notification checksum conflict");
        }
        if (notification.snapshotVersion() == authoritative.snapshotVersion()
                && !notification.snapshotId().equals(authoritative.snapshotId())) {
            throw new SnapshotIntegrityException("same-version notification snapshot identity conflict");
        }
        CacheUpdate update = applyValidated(authoritative);
        processedEvents.add(notification.eventId());
        return switch (update.outcome()) {
            case APPLIED -> ReconcileOutcome.APPLIED_CURRENT;
            case IDEMPOTENT -> ReconcileOutcome.ALREADY_CURRENT;
            case STALE_IGNORED -> ReconcileOutcome.CACHE_AHEAD;
            case SNAPSHOT_ID_CONFLICT -> throw new SnapshotIntegrityException(
                    "same-version authoritative snapshot identity conflict");
            case CHECKSUM_CONFLICT -> throw new SnapshotIntegrityException(
                    "same-version authoritative snapshot checksum conflict");
        };
    }

    public Optional<SnapshotArtifact> cached(EnvironmentScope scope) {
        return cache.get(scope);
    }

    private CacheUpdate applyValidated(SnapshotArtifact snapshot) {
        validator.validate(snapshot);
        CacheUpdate update = cache.apply(snapshot);
        if (update.outcome() == ApplyOutcome.APPLIED) {
            telemetry.snapshotApplied(snapshot);
            listeners.forEach(listener -> listener.onSnapshotApplied(snapshot));
        }
        if (update.outcome() == ApplyOutcome.SNAPSHOT_ID_CONFLICT) {
            throw new SnapshotIntegrityException("same-version snapshot identity conflict");
        }
        if (update.outcome() == ApplyOutcome.CHECKSUM_CONFLICT) {
            throw new SnapshotIntegrityException("same-version snapshot checksum conflict");
        }
        return update;
    }

    public enum ReconcileOutcome {
        APPLIED_CURRENT,
        ALREADY_CURRENT,
        DUPLICATE_EVENT,
        CACHE_AHEAD
    }
}
