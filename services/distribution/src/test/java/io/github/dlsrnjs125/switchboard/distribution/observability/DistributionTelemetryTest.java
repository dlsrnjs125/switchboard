package io.github.dlsrnjs125.switchboard.distribution.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator.ReconcileOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
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
}
