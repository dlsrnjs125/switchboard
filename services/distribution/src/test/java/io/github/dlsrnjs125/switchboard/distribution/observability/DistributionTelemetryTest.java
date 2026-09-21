package io.github.dlsrnjs125.switchboard.distribution.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator.ReconcileOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DistributionTelemetryTest {
    @Test
    void keepsDynamicFailureNamesOutOfMetricCardinality() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP);
        SnapshotNotification notification = new SnapshotNotification(
                UUID.randomUUID(), "tenant-a", "project-a", "production",
                UUID.randomUUID(), 9, "checksum");

        ReconcileOutcome result = telemetry.observeReconcile(
                notification, () -> ReconcileOutcome.APPLIED_CURRENT);
        telemetry.grpcEvent("nack", "failure", "caller-controlled-reason", 9);

        assertEquals(ReconcileOutcome.APPLIED_CURRENT, result);
        assertNotNull(meters.get("switchboard.distribution.reconcile.total")
                .tag("reason", "APPLIED_CURRENT").counter());
        assertNotNull(meters.get("switchboard.distribution.grpc.event.total")
                .tag("reason", "other").counter());
        assertEquals(0, meters.find("switchboard.distribution.grpc.event.total")
                .tag("environment", "production").meters().size());
    }

    @Test
    void ackLatencyStartsAtTheSpecificClientSnapshotSend() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicLong nanoTime = new AtomicLong(TimeUnit.HOURS.toNanos(1));
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, nanoTime::get);
        UUID clientA = UUID.randomUUID();
        UUID clientB = UUID.randomUUID();

        telemetry.snapshotSent(clientA, 9);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        telemetry.snapshotSent(clientB, 9);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
        telemetry.acknowledged(clientA, 9, true);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        telemetry.acknowledged(clientB, 9, true);

        var timer = meters.get("switchboard.distribution.snapshot.ack.latency").timer();
        assertEquals(2L, timer.count());
        assertEquals(90.0, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void ackWithoutAnActualSendDoesNotRecordLatency() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, () -> 0L);

        telemetry.acknowledged(UUID.randomUUID(), 9, true);

        assertEquals(0, meters.find("switchboard.distribution.snapshot.ack.latency")
                .timers().size());
    }
}
