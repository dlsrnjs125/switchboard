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
    void concurrentSessionsSharingAnApplicationRetainIndependentDeliveryLatency() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicLong nanoTime = new AtomicLong(TimeUnit.HOURS.toNanos(1));
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, nanoTime::get);
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID deliveryA = UUID.randomUUID();
        UUID deliveryB = UUID.randomUUID();

        telemetry.snapshotSent(sessionA, deliveryA, 9);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        telemetry.snapshotSent(sessionB, deliveryB, 9);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
        telemetry.acknowledged(deliveryB.toString(), 9, true);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        telemetry.acknowledged(deliveryA.toString(), 9, true);

        var timer = meters.get("switchboard.distribution.snapshot.ack.latency").timer();
        assertEquals(2L, timer.count());
        assertEquals(90.0, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void ackWithoutAnActualSendDoesNotRecordLatency() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, () -> 0L);

        telemetry.acknowledged(UUID.randomUUID().toString(), 9, true);

        assertEquals(0, meters.find("switchboard.distribution.snapshot.ack.latency")
                .timers().size());
    }

    @Test
    void mismatchedVersionDoesNotConsumeDeliveryCorrelation() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicLong nanoTime = new AtomicLong(TimeUnit.HOURS.toNanos(1));
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, nanoTime::get);
        UUID delivery = UUID.randomUUID();

        telemetry.snapshotSent(UUID.randomUUID(), delivery, 9);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        telemetry.acknowledged(delivery.toString(), 10, true);
        nanoTime.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        telemetry.acknowledged(delivery.toString(), 9, true);

        var timer = meters.get("switchboard.distribution.snapshot.ack.latency").timer();
        assertEquals(1L, timer.count());
        assertEquals(20.0, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void closingSessionDiscardsItsUnacknowledgedDeliveries() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP, () -> 10L);
        UUID session = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();

        telemetry.sessionRegistered();
        telemetry.snapshotSent(session, delivery, 9);
        telemetry.sessionUnregistered(session);
        telemetry.acknowledged(delivery.toString(), 9, true);

        assertEquals(0, meters.find("switchboard.distribution.snapshot.ack.latency")
                .timers().size());
    }

    @Test
    void recordsPendingSnapshotBoundsAndCoalescing() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry =
                new DistributionTelemetry(meters, ObservationRegistry.NOOP);

        telemetry.pendingSnapshotChanged(1, 1_024);
        telemetry.snapshotCoalesced();
        telemetry.pendingSnapshotChanged(0, 128);

        assertEquals(1.0, meters.get("switchboard.distribution.snapshot.pending").gauge().value());
        assertEquals(1_152.0,
                meters.get("switchboard.distribution.snapshot.pending.bytes").gauge().value());
        assertEquals(1.0, meters.get("switchboard.distribution.backpressure.total")
                .tag("operation", "coalesce")
                .tag("outcome", "success")
                .tag("reason", "latest_snapshot")
                .counter().count());

        telemetry.pendingSnapshotChanged(-1, -1_152);

        assertEquals(0.0, meters.get("switchboard.distribution.snapshot.pending").gauge().value());
        assertEquals(0.0,
                meters.get("switchboard.distribution.snapshot.pending.bytes").gauge().value());
    }
}
