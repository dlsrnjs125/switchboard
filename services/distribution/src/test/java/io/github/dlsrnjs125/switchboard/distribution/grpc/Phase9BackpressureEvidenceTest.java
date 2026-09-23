package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.grpc.stub.ServerCallStreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("phase9")
@Tag("phase9-backpressure")
class Phase9BackpressureEvidenceTest {
    private static final int[] CLIENT_COUNTS = {100, 500, 1_000};
    private static final int SLOW_CLIENT_PERCENT = 20;
    private static final int WARMUP_UPDATES = 10;
    private static final int BASELINE_UPDATES = 20;
    private static final int PRESSURE_UPDATES = 100;
    private static final long UPDATE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int SNAPSHOT_BYTES = 16 * 1_024;
    private static final long MAX_HEAP_DELTA_BYTES = 32L * 1_024 * 1_024;
    private static final long MAX_HEALTHY_P99_ADDITION_MICROS = 10_000;

    @Test
    void recordsSustainedSlowClientIsolationAndMemoryBounds() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int clients : CLIENT_COUNTS) {
            scenarios.add(runScenario(clients));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-BKP-001");
        result.put("workloadResult", "pass");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("vm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("slowClientPercent", SLOW_CLIENT_PERCENT);
        result.put("snapshotPayloadBytes", SNAPSHOT_BYTES);
        result.put("warmupUpdates", WARMUP_UPDATES);
        result.put("baselineUpdates", BASELINE_UPDATES);
        result.put("pressureUpdates", PRESSURE_UPDATES);
        result.put("updateIntervalMillis", TimeUnit.NANOSECONDS.toMillis(UPDATE_INTERVAL_NANOS));
        result.put("pressureDurationSeconds",
                TimeUnit.NANOSECONDS.toSeconds(PRESSURE_UPDATES * UPDATE_INTERVAL_NANOS));
        result.put("maximumHeapDeltaBytes", MAX_HEAP_DELTA_BYTES);
        result.put("maximumHealthyP99AdditionMicros", MAX_HEALTHY_P99_ADDITION_MICROS);
        result.put("topology", "in-process production ClientSession path with synthetic controllable "
                + "ServerCallStreamObserver readiness");
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.backpressure.result",
                "build/reports/phase-09/backpressure.json"));
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private Map<String, Object> runScenario(int clients) throws Exception {
        int slowClients = clients * SLOW_CLIENT_PERCENT / 100;
        int healthyClients = clients - slowClients;
        BackpressureProbe probe = new BackpressureProbe();
        LatencyRecorder baseline = new LatencyRecorder(healthyClients * BASELINE_UPDATES);
        LatencyRecorder pressure = new LatencyRecorder(healthyClients * PRESSURE_UPDATES);
        List<SessionFixture> healthy = new ArrayList<>(healthyClients);
        List<SessionFixture> slow = new ArrayList<>(slowClients);
        List<SessionFixture> all = new ArrayList<>(clients);

        for (int index = 0; index < healthyClients; index++) {
            SessionFixture fixture = fixture(probe, null);
            healthy.add(fixture);
            all.add(fixture);
        }
        for (int index = 0; index < slowClients; index++) {
            SessionFixture fixture = fixture(probe, null);
            slow.add(fixture);
            all.add(fixture);
        }

        long version = 1;
        LatencyRecorder warmup = new LatencyRecorder(healthyClients * WARMUP_UPDATES);
        for (int update = 0; update < WARMUP_UPDATES; update++) {
            broadcast(all, snapshot(version++), warmup);
        }
        healthy.forEach(fixture -> fixture.observer().recorder(baseline));
        for (int update = 0; update < BASELINE_UPDATES; update++) {
            broadcast(all, snapshot(version++), baseline);
        }
        assertEquals(healthyClients * BASELINE_UPDATES, baseline.size());
        assertEquals(0, probe.pendingCount());

        healthy.forEach(fixture -> fixture.observer().recorder(pressure));
        slow.forEach(fixture -> fixture.observer().ready(false));
        long heapBefore = usedHeapAfterGc();
        long pressureStarted = System.nanoTime();
        for (int update = 0; update < PRESSURE_UPDATES; update++) {
            long scheduled = pressureStarted + (update + 1L) * UPDATE_INTERVAL_NANOS;
            long remaining = scheduled - System.nanoTime();
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            }
            broadcast(all, snapshot(version++), pressure);
        }
        long pressureElapsedNanos = System.nanoTime() - pressureStarted;
        long heapUnderPressure = usedHeapAfterGc();

        assertEquals(healthyClients * PRESSURE_UPDATES, pressure.size());
        assertEquals(slowClients, probe.pendingCount());
        assertEquals(slowClients + 1L, probe.maximumPendingCount());
        assertEquals((long) slowClients * (PRESSURE_UPDATES - 1), probe.coalescedCount());
        assertTrue(probe.pendingBytes() > 0);
        assertTrue(probe.pendingBytes() <= (long) slowClients * (SNAPSHOT_BYTES + 256));

        long expectedFinalVersion = version - 1;
        slow.forEach(fixture -> fixture.observer().ready(true));
        slow.forEach(fixture -> fixture.observer().fireOnReady());
        assertEquals(0, probe.pendingCount());
        assertEquals(0, probe.pendingBytes());
        slow.forEach(fixture -> assertEquals(expectedFinalVersion, fixture.observer().lastSnapshotVersion()));

        long heapAfterDrain = usedHeapAfterGc();
        long heapDelta = Math.max(0, heapUnderPressure - heapBefore);
        Map<String, Long> baselinePercentiles = baseline.percentilesMicros();
        Map<String, Long> pressurePercentiles = pressure.percentilesMicros();
        long healthyP99Addition = pressurePercentiles.get("p99") - baselinePercentiles.get("p99");
        assertTrue(heapDelta <= MAX_HEAP_DELTA_BYTES,
                () -> "heap delta exceeded experiment target: " + heapDelta);
        assertTrue(healthyP99Addition <= MAX_HEALTHY_P99_ADDITION_MICROS,
                () -> "healthy-client p99 addition exceeded experiment target: " + healthyP99Addition);

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("clients", clients);
        scenario.put("healthyClients", healthyClients);
        scenario.put("slowClients", slowClients);
        scenario.put("baselineHealthyDeliveryLatencyMicros", baselinePercentiles);
        scenario.put("pressureHealthyDeliveryLatencyMicros", pressurePercentiles);
        scenario.put("healthyP99AdditionMicros", healthyP99Addition);
        scenario.put("maximumPendingSnapshots", probe.maximumPendingCount());
        scenario.put("maximumTransientReadySessionSlots", 1);
        scenario.put("pendingSnapshotsBeforeDrain", slowClients);
        scenario.put("pendingSerializedBytesBeforeDrain", probe.maximumPendingBytes());
        scenario.put("coalescedSnapshots", probe.coalescedCount());
        scenario.put("heapDeltaAfterGcBytes", heapDelta);
        scenario.put("heapDeltaAfterDrainBytes", Math.max(0, heapAfterDrain - heapBefore));
        scenario.put("pressureElapsedMillis", TimeUnit.NANOSECONDS.toMillis(pressureElapsedNanos));
        scenario.put("slowClientsRecoveredLatestVersion", slowClients);
        scenario.put("errors", 0);
        return scenario;
    }

    private SessionFixture fixture(BackpressureProbe probe, LatencyRecorder recorder) {
        RecordingObserver observer = new RecordingObserver(recorder);
        ClientSession session = new ClientSession(
                UUID.randomUUID(), principal(), observer, 0, () -> { },
                (sessionId, deliveryId, version) -> { }, probe);
        return new SessionFixture(session, observer);
    }

    private void broadcast(
            List<SessionFixture> fixtures, SnapshotArtifact snapshot, LatencyRecorder recorder) {
        recorder.broadcastStarted(System.nanoTime());
        fixtures.forEach(fixture -> fixture.session().offerSnapshot(snapshot));
    }

    private SnapshotArtifact snapshot(long version) {
        byte[] canonical = new byte[SNAPSHOT_BYTES];
        Arrays.fill(canonical, (byte) (version & 0x7f));
        return new SnapshotArtifact(
                UUID.randomUUID(), scope(), version, 1, "a".repeat(64), canonical,
                JsonNodeFactory.instance.objectNode(), Instant.EPOCH);
    }

    private CredentialPrincipal principal() {
        return new CredentialPrincipal(UUID.randomUUID(), UUID.randomUUID(), "orders", scope());
    }

    private EnvironmentScope scope() {
        return new EnvironmentScope(
                UUID.fromString("018f1000-0000-7000-8000-000000000001"),
                UUID.fromString("018f1000-0000-7000-8000-000000000002"),
                UUID.fromString("018f1000-0000-7000-8000-000000000003"),
                "acme", "checkout", "production");
    }

    private long usedHeapAfterGc() throws InterruptedException {
        System.gc();
        Thread.sleep(250);
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record SessionFixture(ClientSession session, RecordingObserver observer) {
    }

    private static final class BackpressureProbe implements ClientSession.BackpressureListener {
        private long pendingCount;
        private long pendingBytes;
        private long maximumPendingCount;
        private long maximumPendingBytes;
        private long coalescedCount;

        @Override
        public synchronized void pendingChanged(int countDelta, long byteDelta) {
            pendingCount += countDelta;
            pendingBytes += byteDelta;
            maximumPendingCount = Math.max(maximumPendingCount, pendingCount);
            maximumPendingBytes = Math.max(maximumPendingBytes, pendingBytes);
        }

        @Override
        public synchronized void coalesced() {
            coalescedCount++;
        }

        synchronized long pendingCount() {
            return pendingCount;
        }

        synchronized long pendingBytes() {
            return pendingBytes;
        }

        synchronized long maximumPendingCount() {
            return maximumPendingCount;
        }

        synchronized long maximumPendingBytes() {
            return maximumPendingBytes;
        }

        synchronized long coalescedCount() {
            return coalescedCount;
        }
    }

    private static final class LatencyRecorder {
        private final long[] samples;
        private int size;
        private long broadcastStarted;

        private LatencyRecorder(int capacity) {
            samples = new long[capacity];
        }

        void broadcastStarted(long started) {
            broadcastStarted = started;
        }

        void record() {
            samples[size++] = System.nanoTime() - broadcastStarted;
        }

        int size() {
            return size;
        }

        Map<String, Long> percentilesMicros() {
            long[] sorted = Arrays.copyOf(samples, size);
            Arrays.sort(sorted);
            return Map.of(
                    "p50", micros(sorted, 0.50),
                    "p95", micros(sorted, 0.95),
                    "p99", micros(sorted, 0.99),
                    "max", TimeUnit.NANOSECONDS.toMicros(sorted[sorted.length - 1]));
        }

        private long micros(long[] sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
            return TimeUnit.NANOSECONDS.toMicros(sorted[index]);
        }
    }

    private static final class RecordingObserver extends ServerCallStreamObserver<ServerMessage> {
        private boolean ready = true;
        private Runnable onReady = () -> { };
        private Runnable onCancel = () -> { };
        private LatencyRecorder recorder;
        private long lastSnapshotVersion;

        private RecordingObserver(LatencyRecorder recorder) {
            this.recorder = recorder;
        }

        void ready(boolean value) {
            ready = value;
        }

        void recorder(LatencyRecorder value) {
            recorder = value;
        }

        void fireOnReady() {
            onReady.run();
        }

        long lastSnapshotVersion() {
            return lastSnapshotVersion;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void setOnCancelHandler(Runnable handler) {
            onCancel = handler;
        }

        @Override
        public void setCompression(String compression) {
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setOnReadyHandler(Runnable handler) {
            onReady = handler;
        }

        @Override
        public void disableAutoInboundFlowControl() {
        }

        @Override
        public void request(int count) {
        }

        @Override
        public void setMessageCompression(boolean enable) {
        }

        @Override
        public void onNext(ServerMessage message) {
            if (message.hasFullSnapshot()) {
                lastSnapshotVersion = message.getFullSnapshot().getSnapshotVersion();
                if (recorder != null) {
                    recorder.record();
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            onCancel.run();
        }

        @Override
        public void onCompleted() {
        }
    }
}
