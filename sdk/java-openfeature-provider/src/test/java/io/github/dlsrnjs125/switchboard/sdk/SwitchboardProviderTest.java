package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwitchboardProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesSnapshotAcknowledgesAndEvaluatesThroughOpenFeatureSpi() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);

        FullSnapshot snapshot = SnapshotTestData.snapshot(1, clock.instant(), false);
        transport.emit(snapshot);
        ProviderEvaluation<Boolean> evaluation = provider.getBooleanEvaluation(
                "checkout-v2", false,
                new ImmutableContext("customer-1", Map.of("plan", new Value("premium"))));

        assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
        assertTrue(evaluation.getValue());
        assertEquals("on", evaluation.getVariant());
        assertEquals(Reason.TARGETING_MATCH.name(), evaluation.getReason());
        assertEquals(1L, evaluation.getFlagMetadata().getLong("snapshotVersion"));
        assertEquals("welcome", provider.getStringEvaluation(
                "banner", "fallback", ImmutableContext.EMPTY).getValue());
        assertEquals(25, provider.getIntegerEvaluation(
                "max-items", 0, ImmutableContext.EMPTY).getValue());
        assertEquals(25.0, provider.getDoubleEvaluation(
                "max-items", 0.0, ImmutableContext.EMPTY).getValue());
        assertEquals("blue", provider.getObjectEvaluation(
                "theme", new Value(), ImmutableContext.EMPTY)
                .getValue().asStructure().getValue("color").asString());
        assertTrue(transport.actions.stream().anyMatch(action -> action.startsWith("ack:1:")));
        provider.shutdown();
    }

    @Test
    void applicationEvaluatesThroughStandardOpenFeatureClient() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(1, clock.instant(), true));
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();

        try {
            api.setProviderAndWait(provider);
            boolean value = api.getClient("orders-service").getBooleanValue(
                    "checkout-v2", false, ImmutableContext.EMPTY);

            assertTrue(value);
            assertEquals("Switchboard", api.getProviderMetadata().getName());
        } finally {
            api.shutdown();
        }
    }

    @Test
    void evaluationRemainsLocalAndUsesLkgAfterDistributionBecomesStale() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(3, clock.instant(), true));
        int transportActionsBeforeEvaluation = transport.actions.size();

        transport.disconnect();
        for (int index = 0; index < 1_000; index++) {
            assertTrue(provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY).getValue());
        }

        ProviderEvaluation<Boolean> stale =
                provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY);
        assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());
        assertEquals(Reason.CACHED.name(), stale.getReason());
        assertTrue(stale.getFlagMetadata().getBoolean("stale"));
        assertEquals(transportActionsBeforeEvaluation, transport.actions.size());
        provider.shutdown();
    }

    @Test
    void restartBootstrapsFromDurableLkgWhileRemoteIsUnavailable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        Path lkg = temporaryDirectory.resolve("lkg.json");
        FakeSnapshotTransport firstTransport = new FakeSnapshotTransport();
        SwitchboardProvider first = provider(clock, firstTransport, lkg);
        first.initialize(ImmutableContext.EMPTY);
        firstTransport.emit(SnapshotTestData.snapshot(4, clock.instant(), true));
        first.shutdown();

        FakeSnapshotTransport offline = new FakeSnapshotTransport();
        SwitchboardProvider restarted = provider(clock, offline, lkg);
        restarted.initialize(ImmutableContext.EMPTY);
        ProviderEvaluation<Boolean> evaluation =
                restarted.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY);

        assertEquals(SwitchboardProviderState.READY_STALE, restarted.switchboardState());
        assertTrue(evaluation.getValue());
        assertEquals(Reason.CACHED.name(), evaluation.getReason());
        assertEquals("start:4", offline.actions.get(0));
        restarted.shutdown();
    }

    @Test
    void invalidUpdateIsNackedAndPreservesReadyStateAndActiveSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(5, clock.instant(), true));

        transport.emit(SnapshotTestData.corruptChecksum(
                SnapshotTestData.snapshot(6, clock.instant(), false)));

        assertEquals(5, provider.lastAppliedVersion());
        assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
        assertTrue(provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY).getValue());
        assertTrue(transport.actions.contains("nack:6:SNAPSHOT_INTEGRITY_FAILURE"));

        transport.emit(SnapshotTestData.snapshot(7, clock.instant(), false));
        assertEquals(7, provider.lastAppliedVersion());
        assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
        provider.shutdown();
    }

    @Test
    void invalidUpdatePreservesReadyStaleStateAndActiveSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(5, clock.instant(), true));
        transport.disconnect();

        transport.emit(SnapshotTestData.corruptChecksum(
                SnapshotTestData.snapshot(6, clock.instant(), false)));

        assertEquals(5, provider.lastAppliedVersion());
        assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());
        assertTrue(provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY).getValue());
        assertTrue(transport.actions.contains("nack:6:SNAPSHOT_INTEGRITY_FAILURE"));
        provider.shutdown();
    }

    @Test
    void postRenameDirectorySyncFailureKeepsActiveAndRestartStateAlignedUntilRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        Path lkg = temporaryDirectory.resolve("lkg.json");
        AtomicInteger syncAttempts = new AtomicInteger();
        DiskLkgStore faultingDisk = new DiskLkgStore(lkg, directory -> {
            if (syncAttempts.incrementAndGet() == 1) {
                throw new IOException("injected parent-directory fsync failure");
            }
        });
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:9090", "credential", "orders", "checkout", "production", lkg,
                Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofMillis(10),
                Duration.ofSeconds(1), 0, clock);
        SwitchboardProvider provider = new SwitchboardProvider(
                config, new SnapshotDecoder(), faultingDisk, transport,
                Executors.newSingleThreadScheduledExecutor());
        provider.initialize(ImmutableContext.EMPTY);
        FullSnapshot versionOne = SnapshotTestData.snapshot(1, clock.instant(), true);

        transport.emit(versionOne);

        assertEquals(1, provider.lastAppliedVersion());
        assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());
        assertTrue(transport.actions.contains("nack:1:LKG_DURABILITY_UNCERTAIN"));
        assertEquals(1, new DiskLkgStore(lkg)
                .load(new SnapshotDecoder(), clock.instant(), Duration.ofDays(7))
                .orElseThrow()
                .snapshotVersion());

        transport.emit(versionOne);

        assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
        assertTrue(transport.actions.stream().anyMatch(action -> action.startsWith("ack:1:")));
        assertEquals(2, syncAttempts.get());
        provider.shutdown();
    }

    @Test
    void immediateStreamDisconnectMarksActiveSnapshotStale() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(1, clock.instant(), true));

        clock.advance(Duration.ofSeconds(1));
        transport.disconnect();

        assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());
        assertEquals(Reason.CACHED.name(), provider.getBooleanEvaluation(
                "checkout-v2", false, ImmutableContext.EMPTY).getReason());
        provider.shutdown();
    }

    @Test
    void olderConflictingAndUnsupportedSnapshotsNeverReplaceLkg() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        FullSnapshot active = SnapshotTestData.snapshot(10, clock.instant(), true);
        transport.emit(active);

        transport.emit(SnapshotTestData.snapshot(9, clock.instant(), false));
        transport.emit(SnapshotTestData.snapshot(10, clock.instant(), false));
        transport.emit(SnapshotTestData.snapshot(11, clock.instant(), false).toBuilder()
                .setSchemaVersion(2)
                .build());

        assertEquals(10, provider.lastAppliedVersion());
        assertTrue(provider.getBooleanEvaluation("checkout-v2", false, ImmutableContext.EMPTY).getValue());
        assertTrue(transport.actions.contains("nack:9:STALE_SNAPSHOT"));
        assertTrue(transport.actions.stream().anyMatch(action -> action.startsWith("nack:10:")));
        assertTrue(transport.actions.contains("nack:11:SNAPSHOT_INTEGRITY_FAILURE"));
        provider.shutdown();
    }

    @Test
    void heartbeatAheadRequestsFullResyncAndFreshHeartbeatRecoversStaleState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        FakeSnapshotTransport transport = new FakeSnapshotTransport();
        SwitchboardProvider provider = provider(clock, transport, temporaryDirectory.resolve("lkg.json"));
        provider.initialize(ImmutableContext.EMPTY);
        transport.emit(SnapshotTestData.snapshot(2, clock.instant(), true));

        transport.heartbeat(3);
        assertTrue(transport.actions.contains("resync:2:HEARTBEAT_VERSION_AHEAD"));
        clock.advance(Duration.ofSeconds(31));
        provider.refreshFreshness();
        assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());

        transport.heartbeat(2);
        assertEquals(SwitchboardProviderState.READY, provider.switchboardState());
        provider.shutdown();
    }

    @Test
    void noLkgReturnsOpenFeatureCodeDefaultAndCorruptDiskIsQuarantined() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        Path lkg = temporaryDirectory.resolve("lkg.json");
        Files.writeString(lkg, "not-json");
        SwitchboardProvider provider = provider(clock, new FakeSnapshotTransport(), lkg);

        provider.initialize(ImmutableContext.EMPTY);
        ProviderEvaluation<Boolean> evaluation =
                provider.getBooleanEvaluation("checkout-v2", true, ImmutableContext.EMPTY);

        assertEquals(SwitchboardProviderState.NOT_READY, provider.switchboardState());
        assertTrue(evaluation.getValue());
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evaluation.getErrorCode());
        assertFalse(Files.exists(lkg));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().contains(".corrupt.")));
        }
        provider.shutdown();
    }

    @Test
    void expiredLkgIsRejectedByConfiguredMaximumAge() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
        Path lkg = temporaryDirectory.resolve("lkg.json");
        FullSnapshot old = SnapshotTestData.snapshot(1, clock.instant().minus(Duration.ofDays(8)), true);
        new DiskLkgStore(lkg).persist(new SnapshotDecoder().decode(old));
        SwitchboardProvider provider = provider(clock, new FakeSnapshotTransport(), lkg);

        provider.initialize(ImmutableContext.EMPTY);

        assertEquals(SwitchboardProviderState.NOT_READY, provider.switchboardState());
        assertEquals(0, provider.lastAppliedVersion());
        assertFalse(Files.exists(lkg));
        provider.shutdown();
    }

    private SwitchboardProvider provider(MutableClock clock, FakeSnapshotTransport transport, Path lkg) {
        SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                "localhost:9090", "credential", "orders", "checkout", "production", lkg,
                Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofMillis(10),
                Duration.ofSeconds(1), 0, clock);
        return new SwitchboardProvider(
                config, new SnapshotDecoder(), new DiskLkgStore(lkg), transport,
                Executors.newSingleThreadScheduledExecutor());
    }
}
