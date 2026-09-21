package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SnapshotCacheTest {
    @Test
    void sameVersionAndChecksumWithDifferentSnapshotIdentityIsConflict() {
        SnapshotCache cache = new SnapshotCache();
        UUID activeId = UUID.randomUUID();
        SnapshotArtifact active = snapshot(activeId);
        SnapshotArtifact conflicting = snapshot(UUID.randomUUID());
        cache.apply(active);

        SnapshotCache.CacheUpdate update = cache.apply(conflicting);

        assertEquals(SnapshotCache.ApplyOutcome.SNAPSHOT_ID_CONFLICT, update.outcome());
        assertEquals(activeId, update.active().snapshotId());
        assertEquals(activeId, cache.get(scope()).orElseThrow().snapshotId());
    }

    private SnapshotArtifact snapshot(UUID snapshotId) {
        return new SnapshotArtifact(
                snapshotId, scope(), 10, 1, "a".repeat(64),
                new byte[] {10}, JsonNodeFactory.instance.objectNode(), Instant.EPOCH);
    }

    private EnvironmentScope scope() {
        return new EnvironmentScope(
                UUID.fromString("018f1000-0000-7000-8000-000000000001"),
                UUID.fromString("018f1000-0000-7000-8000-000000000002"),
                UUID.fromString("018f1000-0000-7000-8000-000000000003"),
                "acme", "checkout", "production");
    }
}
