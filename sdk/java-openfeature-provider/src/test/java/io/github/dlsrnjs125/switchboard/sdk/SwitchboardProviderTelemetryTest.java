package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SwitchboardProviderTelemetryTest {
    @Test
    void exposesBoundedProviderStateAndSnapshotSignalsWithoutEvaluationIdentity() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-09-21T00:01:00Z"), ZoneOffset.UTC);
        SwitchboardProviderTelemetry telemetry =
                new SwitchboardProviderTelemetry(meters, ObservationRegistry.NOOP, clock);
        SdkSnapshot snapshot = new SdkSnapshot(
                UUID.randomUUID(), 7, 1, "checksum",
                Instant.parse("2026-09-21T00:00:30Z"), Map.of(), new byte[] {'{', '}'});

        telemetry.snapshotApply("applied", "APPLIED", snapshot);
        telemetry.stateChanged(SwitchboardProviderState.INITIALIZING, SwitchboardProviderState.READY);

        assertEquals(30.0, meters.get("switchboard.sdk.snapshot.age").gauge().value());
        assertEquals(1.0, meters.get("switchboard.sdk.provider.state")
                .tag("provider_state", "READY").gauge().value());
        assertNotNull(meters.get("switchboard.sdk.snapshot.apply.total")
                .tag("outcome", "applied").counter());
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                String key = tag.getKey().toLowerCase();
                assertFalse(key.contains("targeting"));
                assertFalse(key.contains("user"));
                assertFalse(key.contains("credential"));
                assertFalse(key.contains("environment"));
            });
        }
    }
}
