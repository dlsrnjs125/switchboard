package io.github.dlsrnjs125.switchboard.distribution.observability;

import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator.ReconcileOutcome;
import io.github.dlsrnjs125.switchboard.observability.ObservationNames;
import io.github.dlsrnjs125.switchboard.observability.TelemetryPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DistributionTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistributionTelemetry.class);
    private static final Set<String> SAFE_REASONS = Set.of(
            "none", "capacity", "inactive", "broker_ack", "matching_snapshot",
            "scope_or_snapshot_mismatch", "unspecified", "STALE_SNAPSHOT",
            "SNAPSHOT_ID_CONFLICT", "CHECKSUM_CONFLICT", "SNAPSHOT_INTEGRITY_FAILURE",
            "HEARTBEAT_VERSION_AHEAD", "LKG_DURABILITY_UNCONFIRMED", "CLIENT_VERSION_AHEAD",
            "UNSUPPORTED_SCHEMA_VERSION", "broadcast", "bootstrap", "connected",
            "APPLIED_CURRENT", "ALREADY_CURRENT", "DUPLICATE_EVENT", "CACHE_AHEAD");

    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final LongSupplier nanoTime;
    private final AtomicInteger connectedSessions = new AtomicInteger();
    private final AtomicLong highestCacheVersion = new AtomicLong();
    private final Map<UUID, SentSnapshot> sentSnapshots = new ConcurrentHashMap<>();

    public DistributionTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this(meters, observations, System::nanoTime);
    }

    DistributionTelemetry(
            MeterRegistry meters,
            ObservationRegistry observations,
            LongSupplier nanoTime) {
        this.meters = meters;
        this.observations = observations;
        this.nanoTime = nanoTime;
        Gauge.builder(TelemetryPolicy.metricName("switchboard.distribution.sessions.connected"),
                        connectedSessions, AtomicInteger::get)
                .tags(TelemetryPolicy.metricTags("component", "distribution"))
                .register(meters);
        Gauge.builder(TelemetryPolicy.metricName("switchboard.distribution.cache.version"),
                        highestCacheVersion, AtomicLong::get)
                .tags(TelemetryPolicy.metricTags("component", "distribution"))
                .register(meters);
    }

    public static DistributionTelemetry noop() {
        return new DistributionTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    public void sessionRegistered() {
        connectedSessions.incrementAndGet();
    }

    public void sessionUnregistered(UUID sessionId) {
        connectedSessions.updateAndGet(value -> Math.max(0, value - 1));
        sentSnapshots.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }

    public void admissionRejected() {
        increment("switchboard.distribution.admission.total", "admit", "rejected", "capacity");
    }

    public void credentialRevoked() {
        increment("switchboard.distribution.credential.revoke.total", "revalidate", "revoked", "inactive");
    }

    public ReconcileOutcome observeReconcile(
            SnapshotNotification notification,
            Supplier<ReconcileOutcome> reconcile) {
        Timer.Sample sample = Timer.start(meters);
        Observation observation = Observation.start(ObservationNames.DISTRIBUTION_RECONCILE, observations)
                .lowCardinalityKeyValue("operation", "reconcile")
                .highCardinalityKeyValue("event.id",
                        TelemetryPolicy.traceAttribute("event.id", notification.eventId()))
                .highCardinalityKeyValue("snapshot.version",
                        TelemetryPolicy.traceAttribute("snapshot.version", notification.snapshotVersion()))
                .highCardinalityKeyValue("environment.key",
                        TelemetryPolicy.traceAttribute("environment.key", notification.environmentKey()));
        String outcome = "failure";
        String reason = "exception";
        try (Observation.Scope ignored = observation.openScope()) {
            ReconcileOutcome result = reconcile.get();
            outcome = "success";
            reason = result.name();
            LOGGER.atInfo().addKeyValue("event", "snapshot_reconciled")
                    .addKeyValue("eventId", notification.eventId())
                    .addKeyValue("snapshotVersion", notification.snapshotVersion())
                    .addKeyValue("environmentKey", notification.environmentKey())
                    .addKeyValue("outcome", result.name())
                    .log("distribution snapshot notification reconciled");
            return result;
        } catch (RuntimeException exception) {
            reason = exception.getClass().getSimpleName();
            observation.error(exception);
            throw exception;
        } finally {
            increment("switchboard.distribution.reconcile.total", "reconcile", outcome, reason);
            sample.stop(Timer.builder(TelemetryPolicy.metricName("switchboard.distribution.reconcile.duration"))
                    .tags(TelemetryPolicy.metricTags(
                            "component", "distribution", "operation", "reconcile", "outcome", outcome))
                    .register(meters));
            observation.stop();
        }
    }

    public void snapshotApplied(SnapshotArtifact snapshot) {
        highestCacheVersion.accumulateAndGet(snapshot.snapshotVersion(), Math::max);
    }

    public void grpcEvent(String operation, String outcome, String reason, long snapshotVersion) {
        grpcEvent(operation, outcome, reason, snapshotVersion, null);
    }

    public void grpcEvent(
            String operation,
            String outcome,
            String reason,
            long snapshotVersion,
            UUID clientApplicationId) {
        Observation observation = Observation.start(ObservationNames.DISTRIBUTION_GRPC_EVENT, observations)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("outcome", outcome)
                .highCardinalityKeyValue("snapshot.version",
                        TelemetryPolicy.traceAttribute("snapshot.version", snapshotVersion));
        if (clientApplicationId != null) {
            observation.highCardinalityKeyValue("client.application.id",
                    TelemetryPolicy.traceAttribute("client.application.id", clientApplicationId));
        }
        try (Observation.Scope ignored = observation.openScope()) {
            increment("switchboard.distribution.grpc.event.total", operation, outcome, reason);
            LOGGER.atInfo().addKeyValue("event", "grpc_snapshot_event")
                    .addKeyValue("operation", operation).addKeyValue("outcome", outcome)
                    .addKeyValue("reason", safeReason(reason)).addKeyValue("snapshotVersion", snapshotVersion)
                    .addKeyValue("clientApplicationId", clientApplicationId)
                    .log("distribution gRPC event completed");
        } finally {
            observation.stop();
        }
    }

    public void snapshotSent(UUID sessionId, UUID deliveryId, long snapshotVersion) {
        sentSnapshots.put(deliveryId, new SentSnapshot(sessionId, snapshotVersion, nanoTime.getAsLong()));
    }

    public void acknowledged(String deliveryId, long snapshotVersion, boolean accepted) {
        if (!accepted) {
            return;
        }
        UUID parsedDeliveryId;
        try {
            parsedDeliveryId = UUID.fromString(deliveryId);
        } catch (IllegalArgumentException exception) {
            return;
        }
        SentSnapshot sent = sentSnapshots.get(parsedDeliveryId);
        if (sent == null || sent.snapshotVersion() != snapshotVersion) {
            return;
        }
        if (!sentSnapshots.remove(parsedDeliveryId, sent)) {
            return;
        }
        Timer.builder(TelemetryPolicy.metricName("switchboard.distribution.snapshot.ack.latency"))
                .tags(TelemetryPolicy.metricTags(
                        "component", "distribution", "outcome", "accepted"))
                .register(meters)
                .record(nanoTime.getAsLong() - sent.sentAtNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void increment(String name, String operation, String outcome, String reason) {
        Counter.builder(TelemetryPolicy.metricName(name))
                .tags(TelemetryPolicy.metricTags(
                        "component", "distribution", "operation", operation,
                        "outcome", outcome, "reason", safeReason(reason)))
                .register(meters).increment();
    }

    private String safeReason(String reason) {
        return SAFE_REASONS.contains(reason) ? reason : "other";
    }

    private record SentSnapshot(UUID sessionId, long snapshotVersion, long sentAtNanos) {
    }
}
