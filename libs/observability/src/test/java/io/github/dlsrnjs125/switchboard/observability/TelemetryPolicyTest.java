package io.github.dlsrnjs125.switchboard.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TelemetryPolicyTest {
    @Test
    void acceptsOnlyLowCardinalityMetricDimensions() {
        assertDoesNotThrow(() -> TelemetryPolicy.metricTags(
                "component", "sdk", "operation", "evaluate", "outcome", "success"));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.metricTags("targeting_key", "customer-1"));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.metricTags("environment", "production"));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.metricTags("operation", "Bearer raw-secret"));
    }

    @Test
    void traceAttributesRejectSecretsAndEvaluationIdentity() {
        assertDoesNotThrow(() -> TelemetryPolicy.traceAttribute("correlation.id", "request-1"));
        assertDoesNotThrow(() -> TelemetryPolicy.traceAttribute("snapshot.version", 7));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.traceAttribute("userId", "user-1"));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.traceAttribute("snapshot.payload", "{}"));
        assertThrows(IllegalArgumentException.class, () ->
                TelemetryPolicy.traceAttribute("credential.secret", "raw"));
    }
}
