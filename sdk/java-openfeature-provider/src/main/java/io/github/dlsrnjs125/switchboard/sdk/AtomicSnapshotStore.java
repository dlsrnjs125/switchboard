package io.github.dlsrnjs125.switchboard.sdk;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicSnapshotStore {
    private final AtomicReference<SdkSnapshot> active = new AtomicReference<>();
    private final DiskLkgStore disk;

    public AtomicSnapshotStore(DiskLkgStore disk) {
        this.disk = disk;
    }

    public Optional<SdkSnapshot> current() {
        return Optional.ofNullable(active.get());
    }

    public void bootstrap(SdkSnapshot snapshot) {
        active.set(snapshot);
    }

    public synchronized ApplyResult apply(SdkSnapshot candidate) {
        SdkSnapshot current = active.get();
        if (current != null) {
            if (candidate.snapshotVersion() < current.snapshotVersion()) {
                return ApplyResult.STALE_IGNORED;
            }
            if (candidate.snapshotVersion() == current.snapshotVersion()) {
                if (!candidate.snapshotId().equals(current.snapshotId())) {
                    return ApplyResult.SNAPSHOT_ID_CONFLICT;
                }
                if (!candidate.checksum().equals(current.checksum())) {
                    return ApplyResult.CHECKSUM_CONFLICT;
                }
                return ApplyResult.IDEMPOTENT;
            }
        }
        disk.persist(candidate);
        active.set(candidate);
        return ApplyResult.APPLIED;
    }

    public enum ApplyResult {
        APPLIED,
        IDEMPOTENT,
        STALE_IGNORED,
        SNAPSHOT_ID_CONFLICT,
        CHECKSUM_CONFLICT
    }
}
