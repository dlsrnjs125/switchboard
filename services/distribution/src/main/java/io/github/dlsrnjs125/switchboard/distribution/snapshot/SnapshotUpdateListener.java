package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;

@FunctionalInterface
public interface SnapshotUpdateListener {
    void onSnapshotApplied(SnapshotArtifact snapshot);
}
