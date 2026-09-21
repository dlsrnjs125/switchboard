package io.github.dlsrnjs125.switchboard.controlplane.observability;

import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.PublishResult;
import io.github.dlsrnjs125.switchboard.observability.ObservationNames;
import io.github.dlsrnjs125.switchboard.observability.TelemetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ControlPlaneTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneTelemetry.class);

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public ControlPlaneTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    public static ControlPlaneTelemetry noop() {
        return new ControlPlaneTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    public PublishResult observePublication(
            String operation,
            UUID correlationId,
            Supplier<PublishResult> publication) {
        Timer.Sample sample = Timer.start(meters);
        AtomicReference<PublishResult> prepared = new AtomicReference<>();
        registerTransactionResult(operation, correlationId, prepared);
        Observation observation = Observation.start(ObservationNames.CONTROL_PUBLISH, observations)
                .lowCardinalityKeyValue("operation", operation)
                .highCardinalityKeyValue("correlation.id",
                        TelemetryPolicy.traceAttribute("correlation.id", correlationId));
        String outcome = "success";
        String reason = "none";
        try (Observation.Scope ignored = observation.openScope()) {
            PublishResult result = publication.get();
            prepared.set(result);
            observation.highCardinalityKeyValue("snapshot.version",
                    TelemetryPolicy.traceAttribute("snapshot.version", result.snapshotVersion()));
            LOGGER.atInfo()
                    .addKeyValue("event", "publication_prepared")
                    .addKeyValue("operation", operation)
                    .addKeyValue("correlationId", correlationId)
                    .addKeyValue("snapshotVersion", result.snapshotVersion())
                    .addKeyValue("snapshotId", result.snapshotId())
                    .log("control-plane publication prepared");
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            reason = exception instanceof DomainException domain ? domain.code() : "INTERNAL";
            observation.error(exception);
            throw exception;
        } finally {
            Counter.builder(TelemetryPolicy.metricName("switchboard.control.publish.total"))
                    .tags(TelemetryPolicy.metricTags(
                            "component", "control-plane", "operation", operation,
                            "outcome", outcome, "reason", reason))
                    .register(meters)
                    .increment();
            sample.stop(Timer.builder(TelemetryPolicy.metricName("switchboard.control.publish.duration"))
                    .tags(TelemetryPolicy.metricTags(
                            "component", "control-plane", "operation", operation,
                            "outcome", outcome))
                    .register(meters));
            observation.stop();
        }
    }

    public <T> T recordSnapshotCompile(Supplier<T> compiler) {
        return record("switchboard.control.snapshot.compile.duration", "compile", compiler);
    }

    public void recordSnapshotValidation(Runnable validator) {
        record("switchboard.control.snapshot.validation.duration", "validate", () -> {
            validator.run();
            return null;
        });
    }

    private <T> T record(String metricName, String operation, Supplier<T> action) {
        return Timer.builder(TelemetryPolicy.metricName(metricName))
                .tags(TelemetryPolicy.metricTags("component", "control-plane", "operation", operation))
                .register(meters)
                .record(action);
    }

    private void registerTransactionResult(
            String operation,
            UUID correlationId,
            AtomicReference<PublishResult> prepared) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                PublishResult result = prepared.get();
                if (result != null) {
                    LOGGER.atInfo()
                            .addKeyValue("event", "publication_committed")
                            .addKeyValue("operation", operation)
                            .addKeyValue("correlationId", correlationId)
                            .addKeyValue("snapshotVersion", result.snapshotVersion())
                            .addKeyValue("snapshotId", result.snapshotId())
                            .log("control-plane publication transaction committed");
                }
            }

            @Override
            public void afterCompletion(int status) {
                String outcome = switch (status) {
                    case STATUS_COMMITTED -> "committed";
                    case STATUS_ROLLED_BACK -> "rolled_back";
                    default -> "unknown";
                };
                Counter.builder(TelemetryPolicy.metricName("switchboard.control.transaction.total"))
                        .tags(TelemetryPolicy.metricTags(
                                "component", "control-plane", "operation", operation, "outcome", outcome))
                        .register(meters).increment();
            }
        });
    }
}
