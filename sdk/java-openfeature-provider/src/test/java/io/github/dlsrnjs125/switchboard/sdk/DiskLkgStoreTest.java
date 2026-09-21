package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskLkgStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicReplacementSurvivesRestartAndIgnoresInterruptedTemporaryArtifact() throws Exception {
        Path lkgPath = temporaryDirectory.resolve("lkg.json");
        DiskLkgStore firstProcess = new DiskLkgStore(lkgPath);
        SnapshotDecoder decoder = new SnapshotDecoder();
        Instant now = Instant.parse("2026-09-21T00:00:00Z");
        firstProcess.persist(decoder.decode(SnapshotTestData.snapshot(1, now, false)));

        Files.writeString(temporaryDirectory.resolve("lkg.json.interrupted.tmp"), "partial");
        DiskLkgStore restartedProcess = new DiskLkgStore(lkgPath);
        SdkSnapshot recovered = restartedProcess.load(decoder, now, Duration.ofDays(7)).orElseThrow();

        assertEquals(1, recovered.snapshotVersion());
        assertTrue(Files.isRegularFile(lkgPath));

        restartedProcess.persist(decoder.decode(SnapshotTestData.snapshot(2, now.plusSeconds(1), true)));
        assertEquals(2, new DiskLkgStore(lkgPath)
                .load(decoder, now.plusSeconds(1), Duration.ofDays(7))
                .orElseThrow()
                .snapshotVersion());
    }
}
