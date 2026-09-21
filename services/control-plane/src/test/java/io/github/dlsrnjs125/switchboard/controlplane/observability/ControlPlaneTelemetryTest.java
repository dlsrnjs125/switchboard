package io.github.dlsrnjs125.switchboard.controlplane.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.PublishResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ControlPlaneTelemetryTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackAfterPreparedBodyNeverIncrementsFinalPublishSuccess() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ControlPlaneTelemetry telemetry =
                new ControlPlaneTelemetry(meters, ObservationRegistry.NOOP);
        TransactionSynchronizationManager.initSynchronization();

        telemetry.observePublication("publish", UUID.randomUUID(), () -> result());

        assertEquals(1.0, meters.get("switchboard.control.publish.prepared.total")
                .tag("outcome", "success").counter().count());
        assertNull(meters.find("switchboard.control.publish.total")
                .tag("outcome", "success").counter());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        assertNull(meters.find("switchboard.control.publish.total")
                .tag("outcome", "success").counter());
        assertEquals(1.0, meters.get("switchboard.control.publish.total")
                .tag("outcome", "failure")
                .tag("reason", "transaction_rollback")
                .counter().count());
        assertEquals(1.0, meters.get("switchboard.control.transaction.total")
                .tag("outcome", "rolled_back").counter().count());
    }

    @Test
    void finalSuccessIsRecordedOnlyAfterCommitCompletion() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ControlPlaneTelemetry telemetry =
                new ControlPlaneTelemetry(meters, ObservationRegistry.NOOP);
        TransactionSynchronizationManager.initSynchronization();

        telemetry.observePublication("publish", UUID.randomUUID(), () -> result());
        assertNull(meters.find("switchboard.control.publish.total")
                .tag("outcome", "success").counter());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_COMMITTED));

        assertEquals(1.0, meters.get("switchboard.control.publish.total")
                .tag("outcome", "success").counter().count());
        assertEquals(1L, meters.get("switchboard.control.publish.duration")
                .tag("outcome", "success").timer().count());
    }

    private PublishResult result() {
        return new PublishResult(UUID.randomUUID(), 1, "checksum");
    }
}
