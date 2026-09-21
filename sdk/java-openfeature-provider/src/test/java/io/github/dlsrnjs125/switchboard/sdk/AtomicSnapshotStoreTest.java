package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicSnapshotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameVersionIdentityAndChecksumConflictsKeepExistingSnapshot() {
        SnapshotDecoder decoder = new SnapshotDecoder();
        SdkSnapshot active = decoder.decode(SnapshotTestData.snapshot(
                UUID.randomUUID(), 10, Instant.parse("2026-09-21T00:00:00Z"), true));
        AtomicSnapshotStore store = new AtomicSnapshotStore(
                new DiskLkgStore(temporaryDirectory.resolve("lkg.json")));
        assertEquals(AtomicSnapshotStore.ApplyResult.APPLIED, store.apply(active));
        SdkSnapshot differentIdentity = new SdkSnapshot(
                UUID.randomUUID(), active.snapshotVersion(), active.schemaVersion(), active.checksum(),
                active.generatedAt(), active.flags(), active.canonicalJson());
        SdkSnapshot differentChecksum = new SdkSnapshot(
                active.snapshotId(), active.snapshotVersion(), active.schemaVersion(), "f".repeat(64),
                active.generatedAt(), active.flags(), active.canonicalJson());

        assertEquals(
                AtomicSnapshotStore.ApplyResult.SNAPSHOT_ID_CONFLICT,
                store.apply(differentIdentity));
        assertEquals(
                AtomicSnapshotStore.ApplyResult.CHECKSUM_CONFLICT,
                store.apply(differentChecksum));
        assertEquals(active.snapshotId(), store.current().orElseThrow().snapshotId());
        assertEquals(active.checksum(), store.current().orElseThrow().checksum());
    }

    @Test
    void olderSnapshotNeverRegressesActiveVersion() {
        SnapshotDecoder decoder = new SnapshotDecoder();
        AtomicSnapshotStore store = new AtomicSnapshotStore(
                new DiskLkgStore(temporaryDirectory.resolve("lkg.json")));
        SdkSnapshot versionTwo = decoder.decode(
                SnapshotTestData.snapshot(2, Instant.parse("2026-09-21T00:00:00Z"), true));
        SdkSnapshot versionOne = decoder.decode(
                SnapshotTestData.snapshot(1, Instant.parse("2026-09-21T00:00:00Z"), false));
        store.apply(versionTwo);

        assertEquals(AtomicSnapshotStore.ApplyResult.STALE_IGNORED, store.apply(versionOne));
        assertEquals(2, store.current().orElseThrow().snapshotVersion());
    }

    @Test
    void renameCommitKeepsMemoryAndRestartStateAlignedWhenDirectorySyncFails() {
        SnapshotDecoder decoder = new SnapshotDecoder();
        Path lkg = temporaryDirectory.resolve("lkg.json");
        AtomicInteger syncAttempts = new AtomicInteger();
        DiskLkgStore faultingDisk = new DiskLkgStore(lkg, directory -> {
            if (syncAttempts.incrementAndGet() == 2) {
                throw new IOException("injected parent-directory fsync failure");
            }
        });
        AtomicSnapshotStore store = new AtomicSnapshotStore(faultingDisk);
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        SdkSnapshot versionTen = decoder.decode(SnapshotTestData.snapshot(10, now, true));
        SdkSnapshot versionEleven = decoder.decode(SnapshotTestData.snapshot(11, now.plusSeconds(1), false));

        assertEquals(AtomicSnapshotStore.ApplyResult.APPLIED, store.apply(versionTen));
        assertEquals(
                AtomicSnapshotStore.ApplyResult.APPLIED_DURABILITY_UNCERTAIN,
                store.apply(versionEleven));
        assertEquals(11, store.current().orElseThrow().snapshotVersion());
        assertEquals(11, new DiskLkgStore(lkg)
                .load(decoder, now.plusSeconds(1), java.time.Duration.ofDays(7))
                .orElseThrow()
                .snapshotVersion());

        assertEquals(AtomicSnapshotStore.ApplyResult.DURABILITY_CONFIRMED, store.apply(versionEleven));
        assertEquals(3, syncAttempts.get());
    }
}
