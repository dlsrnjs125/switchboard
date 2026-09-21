package io.github.dlsrnjs125.switchboard.controlplane.observability;

import io.github.dlsrnjs125.switchboard.observability.ObservationNames;
import io.github.dlsrnjs125.switchboard.observability.TelemetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxTelemetry.class);

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    private static final String DELIVERY_FAILURE_REASON = "publisher_error";

    public OutboxTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        Gauge.builder(TelemetryPolicy.metricName("switchboard.outbox.pending"), pendingCount, AtomicLong::get)
                .tags(TelemetryPolicy.metricTags("component", "control-plane"))
                .register(meters);
        Gauge.builder(TelemetryPolicy.metricName("switchboard.outbox.oldest.pending.age"),
                        oldestPendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .tags(TelemetryPolicy.metricTags("component", "control-plane"))
                .register(meters);
    }

    public static OutboxTelemetry noop() {
        return new OutboxTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    public Observation startDelivery(UUID eventId) {
        return Observation.start(ObservationNames.OUTBOX_DELIVERY, observations)
                .lowCardinalityKeyValue("operation", "publish")
                .highCardinalityKeyValue("event.id", TelemetryPolicy.traceAttribute("event.id", eventId));
    }

    public void deliveryCompleted(UUID eventId, int attempt, Duration acknowledgementLatency) {
        Counter.builder(TelemetryPolicy.metricName("switchboard.outbox.delivery.total"))
                .tags(TelemetryPolicy.metricTags(
                        "component", "control-plane", "outcome", "success", "reason", "broker_ack"))
                .register(meters).increment();
        Timer.builder(TelemetryPolicy.metricName("switchboard.outbox.ack.latency"))
                .tags(TelemetryPolicy.metricTags("component", "control-plane", "outcome", "success"))
                .register(meters).record(acknowledgementLatency);
        LOGGER.atInfo().addKeyValue("event", "outbox_published")
                .addKeyValue("eventId", eventId).addKeyValue("attempt", attempt)
                .addKeyValue("ackLatencyMs", acknowledgementLatency.toMillis())
                .log("outbox event broker acknowledgement persisted");
    }

    public void deliveryFailed(UUID eventId, int attempt, RuntimeException exception) {
        Counter.builder(TelemetryPolicy.metricName("switchboard.outbox.delivery.total"))
                .tags(TelemetryPolicy.metricTags(
                        "component", "control-plane", "outcome", "failure",
                        "reason", DELIVERY_FAILURE_REASON))
                .register(meters).increment();
        LOGGER.atWarn().addKeyValue("event", "outbox_publish_failed")
                .addKeyValue("eventId", eventId).addKeyValue("attempt", attempt)
                .addKeyValue("errorType", exception.getClass().getSimpleName())
                .log("outbox delivery failed");
    }

    public void updateBacklog(long count, Duration oldestAge) {
        pendingCount.set(count);
        oldestPendingAgeSeconds.set(Math.max(0, oldestAge.toSeconds()));
    }
}
