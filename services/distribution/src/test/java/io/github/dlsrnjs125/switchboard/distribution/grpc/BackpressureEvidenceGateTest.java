package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BackpressureEvidenceGateTest {
    @Test
    void deliberateSlowPathDelayFailsTheRateGate() {
        var delayed = new Phase9BackpressureEvidenceTest.ScheduledRun(
                TimeUnit.SECONDS.toNanos(20),
                100,
                Map.of("p50", 1L, "p95", 1L, "p99", 1L, "max", 1L));

        assertThrows(IllegalStateException.class,
                () -> BackpressureEvidenceGate.verify(delayed, 25_000, 9.5));
    }

    @Test
    void deliberateSchedulerDelayFailsTheLatencyGate() {
        var delayed = new Phase9BackpressureEvidenceTest.ScheduledRun(
                TimeUnit.SECONDS.toNanos(10),
                100,
                Map.of("p50", 1L, "p95", 1L, "p99", 50_000L, "max", 50_000L));

        assertThrows(IllegalStateException.class,
                () -> BackpressureEvidenceGate.verify(delayed, 25_000, 9.5));
    }
}
