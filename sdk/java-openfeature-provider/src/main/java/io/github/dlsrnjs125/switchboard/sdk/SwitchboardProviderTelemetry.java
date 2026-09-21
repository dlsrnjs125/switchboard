package io.github.dlsrnjs125.switchboard.sdk;

import dev.openfeature.sdk.ProviderEvaluation;
import io.github.dlsrnjs125.switchboard.observability.ObservationNames;
import io.github.dlsrnjs125.switchboard.observability.TelemetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class SwitchboardProviderTelemetry {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final Clock clock;
    private final AtomicReference<SwitchboardProviderState> state =
            new AtomicReference<>(SwitchboardProviderState.INITIALIZING);
    private final AtomicLong snapshotGeneratedAtMillis = new AtomicLong();
    private final AtomicLong staleSinceNanos = new AtomicLong();

    public SwitchboardProviderTelemetry(
            MeterRegistry meters,
            ObservationRegistry observations,
            Clock clock) {
        this.meters = meters;
        this.observations = observations;
        this.clock = clock;
        for (SwitchboardProviderState providerState : SwitchboardProviderState.values()) {
            Gauge.builder(TelemetryPolicy.metricName("switchboard.sdk.provider.state"), state,
                            current -> current.get() == providerState ? 1 : 0)
                    .tags(TelemetryPolicy.metricTags(
                            "component", "sdk", "provider_state", providerState.name()))
                    .register(meters);
        }
        Gauge.builder(TelemetryPolicy.metricName("switchboard.sdk.snapshot.age"),
                        snapshotGeneratedAtMillis,
                        generatedAt -> generatedAt.get() == 0 ? 0
                                : Math.max(0, clock.millis() - generatedAt.get()) / 1_000.0)
                .baseUnit("seconds")
                .tags(TelemetryPolicy.metricTags("component", "sdk"))
                .register(meters);
    }

    public static SwitchboardProviderTelemetry global(Clock clock) {
        return new SwitchboardProviderTelemetry(Metrics.globalRegistry, ObservationRegistry.NOOP, clock);
    }

    public <T> ProviderEvaluation<T> observeEvaluation(
            String resultType,
            Supplier<ProviderEvaluation<T>> evaluation) {
        Timer.Sample sample = Timer.start(meters);
        Observation observation = Observation.start(ObservationNames.SDK_EVALUATION, observations)
                .lowCardinalityKeyValue("result_type", resultType);
        String outcome = "failure";
        String reason = "ERROR";
        String errorCode = "none";
        try (Observation.Scope ignored = observation.openScope()) {
            ProviderEvaluation<T> result = evaluation.get();
            reason = result.getReason() == null ? "UNKNOWN" : result.getReason();
            errorCode = result.getErrorCode() == null ? "none" : result.getErrorCode().name();
            outcome = result.getErrorCode() == null ? "success" : "failure";
            return result;
        } finally {
            sample.stop(Timer.builder(TelemetryPolicy.metricName("switchboard.sdk.evaluation.duration"))
                    .tags(TelemetryPolicy.metricTags(
                            "component", "sdk", "result_type", resultType, "outcome", outcome,
                            "reason", reason, "error_code", errorCode))
                    .register(meters));
            observation.stop();
        }
    }

    public void snapshotApply(String outcome, String reason, SdkSnapshot snapshot) {
        snapshotApply(outcome, reason, snapshot.snapshotVersion());
        if ("applied".equals(outcome)) {
            snapshotGeneratedAtMillis.set(snapshot.generatedAt().toEpochMilli());
        }
    }

    public void snapshotApply(String outcome, String reason, long snapshotVersion) {
        Counter.builder(TelemetryPolicy.metricName("switchboard.sdk.snapshot.apply.total"))
                .tags(TelemetryPolicy.metricTags(
                        "component", "sdk", "outcome", outcome, "reason", reason))
                .register(meters).increment();
        Observation observation = Observation.start(ObservationNames.SDK_SNAPSHOT_APPLY, observations)
                .lowCardinalityKeyValue("outcome", outcome)
                .lowCardinalityKeyValue("reason", reason)
                .highCardinalityKeyValue("snapshot.version",
                        TelemetryPolicy.traceAttribute("snapshot.version", snapshotVersion));
        observation.stop();
    }

    public void lkgBootstrap(String outcome) {
        increment("switchboard.sdk.lkg.bootstrap.total", "bootstrap", outcome, outcome);
    }

    public void reconnectScheduled(Duration delay) {
        increment("switchboard.sdk.reconnect.total", "reconnect", "scheduled", "stream_closed");
        Timer.builder(TelemetryPolicy.metricName("switchboard.sdk.reconnect.delay"))
                .tags(TelemetryPolicy.metricTags("component", "sdk", "outcome", "scheduled"))
                .register(meters).record(delay);
    }

    public void stateChanged(SwitchboardProviderState previous, SwitchboardProviderState next) {
        state.set(next);
        Counter.builder(TelemetryPolicy.metricName("switchboard.sdk.state.transition.total"))
                .tags(TelemetryPolicy.metricTags(
                        "component", "sdk", "provider_state", next.name(), "outcome", "transition"))
                .register(meters).increment();
        if (next == SwitchboardProviderState.READY_STALE && previous != SwitchboardProviderState.READY_STALE) {
            staleSinceNanos.set(System.nanoTime());
        } else if (previous == SwitchboardProviderState.READY_STALE && next != SwitchboardProviderState.READY_STALE) {
            long started = staleSinceNanos.getAndSet(0);
            if (started != 0) {
                Timer.builder(TelemetryPolicy.metricName("switchboard.sdk.ready.stale.duration"))
                        .tags(TelemetryPolicy.metricTags(
                                "component", "sdk", "outcome", next.name().toLowerCase()))
                        .register(meters)
                        .record(Duration.ofNanos(System.nanoTime() - started));
            }
        }
    }

    public void activeSnapshot(Instant generatedAt) {
        snapshotGeneratedAtMillis.set(generatedAt.toEpochMilli());
    }

    private void increment(String name, String operation, String outcome, String reason) {
        Counter.builder(TelemetryPolicy.metricName(name))
                .tags(TelemetryPolicy.metricTags(
                        "component", "sdk", "operation", operation, "outcome", outcome, "reason", reason))
                .register(meters).increment();
    }
}
