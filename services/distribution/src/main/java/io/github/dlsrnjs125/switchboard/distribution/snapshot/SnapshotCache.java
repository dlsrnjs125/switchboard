package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class SnapshotCache {
    private final ConcurrentHashMap<EnvironmentScope, SnapshotArtifact> snapshots = new ConcurrentHashMap<>();

    public CacheUpdate apply(SnapshotArtifact candidate) {
        AtomicReference<ApplyOutcome> outcome = new AtomicReference<>();
        SnapshotArtifact active = snapshots.compute(candidate.scope(), (scope, current) -> {
            if (current == null || candidate.snapshotVersion() > current.snapshotVersion()) {
                outcome.set(ApplyOutcome.APPLIED);
                return candidate;
            }
            if (candidate.snapshotVersion() < current.snapshotVersion()) {
                outcome.set(ApplyOutcome.STALE_IGNORED);
                return current;
            }
            if (!candidate.snapshotId().equals(current.snapshotId())) {
                outcome.set(ApplyOutcome.SNAPSHOT_ID_CONFLICT);
                return current;
            }
            if (!candidate.checksum().equals(current.checksum())) {
                outcome.set(ApplyOutcome.CHECKSUM_CONFLICT);
                return current;
            }
            outcome.set(ApplyOutcome.IDEMPOTENT);
            return current;
        });
        return new CacheUpdate(outcome.get(), active);
    }

    public Optional<SnapshotArtifact> get(EnvironmentScope scope) {
        return Optional.ofNullable(snapshots.get(scope));
    }

    public enum ApplyOutcome {
        APPLIED,
        IDEMPOTENT,
        STALE_IGNORED,
        SNAPSHOT_ID_CONFLICT,
        CHECKSUM_CONFLICT
    }

    public record CacheUpdate(ApplyOutcome outcome, SnapshotArtifact active) {
    }
}
