package io.github.dlsrnjs125.switchboard.sdk;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicSnapshotStore {
    private final AtomicReference<SdkSnapshot> active = new AtomicReference<>();
    private final DiskLkgStore disk;
    private boolean activeDurable = true;

    public AtomicSnapshotStore(DiskLkgStore disk) {
        this.disk = disk;
    }

    public Optional<SdkSnapshot> current() {
        return Optional.ofNullable(active.get());
    }

    public synchronized boolean currentDurable() {
        return active.get() != null && activeDurable;
    }

    public synchronized void bootstrap(SdkSnapshot snapshot) {
        active.set(snapshot);
        activeDurable = true;
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
                if (!activeDurable) {
                    if (disk.confirmDurability()) {
                        activeDurable = true;
                        return ApplyResult.DURABILITY_CONFIRMED;
                    }
                    return ApplyResult.APPLIED_DURABILITY_UNCERTAIN;
                }
                return ApplyResult.IDEMPOTENT;
            }
        }
        DiskLkgStore.PersistenceResult persistence = disk.persist(candidate);
        active.set(candidate);
        activeDurable = persistence == DiskLkgStore.PersistenceResult.DURABLE;
        return activeDurable ? ApplyResult.APPLIED : ApplyResult.APPLIED_DURABILITY_UNCERTAIN;
    }

    public enum ApplyResult {
        APPLIED,
        APPLIED_DURABILITY_UNCERTAIN,
        DURABILITY_CONFIRMED,
        IDEMPOTENT,
        STALE_IGNORED,
        SNAPSHOT_ID_CONFLICT,
        CHECKSUM_CONFLICT
    }
}
