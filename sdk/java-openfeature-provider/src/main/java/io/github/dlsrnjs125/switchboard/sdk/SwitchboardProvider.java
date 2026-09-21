package io.github.dlsrnjs125.switchboard.sdk;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import io.github.dlsrnjs125.switchboard.evaluation.EvaluationEngine;
import io.github.dlsrnjs125.switchboard.evaluation.EvaluationErrorCode;
import io.github.dlsrnjs125.switchboard.evaluation.EvaluationResult;
import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition;
import io.github.dlsrnjs125.switchboard.evaluation.FlagValue;
import io.github.dlsrnjs125.switchboard.evaluation.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

public final class SwitchboardProvider extends EventProvider implements SnapshotTransport.Listener {
    public static final String PROVIDER_NAME = "Switchboard";

    private final SwitchboardProviderConfig config;
    private final SnapshotDecoder decoder;
    private final AtomicSnapshotStore snapshots;
    private final DiskLkgStore disk;
    private final SnapshotTransport transport;
    private final EvaluationEngine evaluator;
    private final ScheduledExecutorService freshnessScheduler;
    private final SwitchboardProviderTelemetry telemetry;
    private final AtomicReference<SwitchboardProviderState> state =
            new AtomicReference<>(SwitchboardProviderState.INITIALIZING);
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile Instant lastRemoteContact;

    public SwitchboardProvider(SwitchboardProviderConfig config) {
        this(config, SwitchboardProviderTelemetry.global(config.clock()));
    }

    public SwitchboardProvider(
            SwitchboardProviderConfig config,
            MeterRegistry meters,
            ObservationRegistry observations) {
        this(config, new SwitchboardProviderTelemetry(meters, observations, config.clock()));
    }

    private SwitchboardProvider(SwitchboardProviderConfig config, SwitchboardProviderTelemetry telemetry) {
        this(config, new SnapshotDecoder(), new DiskLkgStore(config.lkgPath()), null,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "switchboard-provider-freshness");
                    thread.setDaemon(true);
                    return thread;
                }), telemetry);
    }

    SwitchboardProvider(
            SwitchboardProviderConfig config,
            SnapshotDecoder decoder,
            DiskLkgStore disk,
            SnapshotTransport transport,
            ScheduledExecutorService freshnessScheduler) {
        this(config, decoder, disk, transport, freshnessScheduler,
                SwitchboardProviderTelemetry.global(config.clock()));
    }

    SwitchboardProvider(
            SwitchboardProviderConfig config,
            SnapshotDecoder decoder,
            DiskLkgStore disk,
            SnapshotTransport transport,
            ScheduledExecutorService freshnessScheduler,
            SwitchboardProviderTelemetry telemetry) {
        this.config = config;
        this.decoder = decoder;
        this.disk = disk;
        this.snapshots = new AtomicSnapshotStore(disk);
        this.telemetry = telemetry;
        this.transport = transport == null ? new GrpcSnapshotTransport(config, telemetry) : transport;
        this.evaluator = new EvaluationEngine();
        this.freshnessScheduler = freshnessScheduler;
    }

    @Override
    public Metadata getMetadata() {
        return () -> PROVIDER_NAME;
    }

    @Override
    public void initialize(EvaluationContext evaluationContext) {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        Optional<SdkSnapshot> lkg = disk.load(decoder, config.clock().instant(), config.maxLkgAge());
        if (lkg.isPresent()) {
            snapshots.bootstrap(lkg.orElseThrow());
            telemetry.lkgBootstrap("loaded");
            telemetry.activeSnapshot(lkg.orElseThrow().generatedAt());
            transition(SwitchboardProviderState.READY_STALE, "durable LKG loaded");
        } else {
            telemetry.lkgBootstrap("missing_or_invalid");
            transition(SwitchboardProviderState.NOT_READY, "no valid snapshot available");
        }
        long intervalMillis = Math.max(100, config.staleAfter().toMillis() / 2);
        freshnessScheduler.scheduleWithFixedDelay(
                this::refreshFreshness, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        transport.start(this::lastAppliedVersion, this);
    }

    @Override
    public void shutdown() {
        if (state.getAndSet(SwitchboardProviderState.CLOSED) != SwitchboardProviderState.CLOSED) {
            transport.close();
            freshnessScheduler.shutdownNow();
            super.shutdown();
        }
    }

    @Override
    public ProviderState getState() {
        return switch (state.get()) {
            case INITIALIZING, NOT_READY -> ProviderState.NOT_READY;
            case READY -> ProviderState.READY;
            case READY_STALE -> ProviderState.STALE;
            case ERROR -> ProviderState.ERROR;
            case CLOSED -> ProviderState.FATAL;
        };
    }

    public SwitchboardProviderState switchboardState() {
        return state.get();
    }

    public long lastAppliedVersion() {
        return snapshots.current().map(SdkSnapshot::snapshotVersion).orElse(0L);
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(
            String key, Boolean defaultValue, EvaluationContext context) {
        return evaluate(key, defaultValue, ValueType.BOOLEAN, context, value ->
                ((FlagValue.BooleanValue) value).value());
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(
            String key, String defaultValue, EvaluationContext context) {
        return evaluate(key, defaultValue, ValueType.STRING, context, value ->
                ((FlagValue.StringValue) value).value());
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(
            String key, Integer defaultValue, EvaluationContext context) {
        return evaluate(key, defaultValue, ValueType.NUMBER, context, value -> {
            try {
                return ((FlagValue.NumberValue) value).value().intValueExact();
            } catch (ArithmeticException exception) {
                throw new TypeConversionException("number cannot be represented as an integer", exception);
            }
        });
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(
            String key, Double defaultValue, EvaluationContext context) {
        return evaluate(key, defaultValue, ValueType.NUMBER, context, value ->
                ((FlagValue.NumberValue) value).value().doubleValue());
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(
            String key, Value defaultValue, EvaluationContext context) {
        return evaluate(key, defaultValue, ValueType.OBJECT, context, value -> {
            Map<String, Object> object = ((FlagValue.ObjectValue) value).value();
            return new Value(Structure.mapToStructure(object));
        });
    }

    @Override
    public void onConnected() {
        lastRemoteContact = config.clock().instant();
    }

    @Override
    public void onSnapshot(FullSnapshot message) {
        lastRemoteContact = config.clock().instant();
        try {
            SdkSnapshot candidate = decoder.decode(message);
            AtomicSnapshotStore.ApplyResult result = snapshots.apply(candidate);
            telemetry.snapshotApply(
                    result == AtomicSnapshotStore.ApplyResult.APPLIED
                            || result == AtomicSnapshotStore.ApplyResult.APPLIED_DURABILITY_UNCERTAIN
                            ? "applied" : "ignored",
                    result.name(), candidate);
            switch (result) {
                case APPLIED -> {
                    transition(SwitchboardProviderState.READY, "snapshot applied");
                    transport.acknowledge(candidate.snapshotVersion(), candidate.checksum());
                    emitProviderConfigurationChanged(ProviderEventDetails.builder()
                            .flagsChanged(candidate.flags().keySet().stream().sorted().toList())
                            .message("snapshot " + candidate.snapshotVersion() + " applied")
                            .build());
                }
                case APPLIED_DURABILITY_UNCERTAIN -> {
                    transition(SwitchboardProviderState.READY_STALE,
                            "snapshot active but LKG directory durability is unconfirmed");
                    emitProviderConfigurationChanged(ProviderEventDetails.builder()
                            .flagsChanged(candidate.flags().keySet().stream().sorted().toList())
                            .message("snapshot " + candidate.snapshotVersion()
                                    + " applied with unconfirmed LKG durability")
                            .build());
                    transport.reject(
                            candidate.snapshotVersion(),
                            "LKG_DURABILITY_UNCERTAIN",
                            "snapshot is active but parent-directory fsync must be retried");
                }
                case DURABILITY_CONFIRMED -> {
                    transition(SwitchboardProviderState.READY, "snapshot LKG durability confirmed");
                    transport.acknowledge(candidate.snapshotVersion(), candidate.checksum());
                }
                case IDEMPOTENT -> {
                    transition(SwitchboardProviderState.READY, "snapshot already active");
                    transport.acknowledge(candidate.snapshotVersion(), candidate.checksum());
                }
                case STALE_IGNORED -> transport.reject(
                        candidate.snapshotVersion(), "STALE_SNAPSHOT", "snapshot version is below active LKG");
                case SNAPSHOT_ID_CONFLICT -> rejectIntegrity(
                        candidate, "SNAPSHOT_ID_CONFLICT", "same version has a different snapshot identity");
                case CHECKSUM_CONFLICT -> rejectIntegrity(
                        candidate, "CHECKSUM_CONFLICT", "same version has a different checksum");
            }
        } catch (RuntimeException exception) {
            telemetry.snapshotApply("rejected", "INTEGRITY_FAILURE", message.getSnapshotVersion());
            rejectCandidate(
                    message.getSnapshotVersion(),
                    "SNAPSHOT_INTEGRITY_FAILURE",
                    "snapshot rejected: " + safeMessage(exception));
        }
    }

    @Override
    public void onHeartbeat(long currentSnapshotVersion) {
        lastRemoteContact = config.clock().instant();
        long local = lastAppliedVersion();
        if (currentSnapshotVersion > local) {
            transport.requestResync(local, "HEARTBEAT_VERSION_AHEAD");
            return;
        }
        if (local > 0 && !snapshots.currentDurable()) {
            transport.requestResync(local, "LKG_DURABILITY_UNCONFIRMED");
            return;
        }
        if (local > 0) {
            transition(SwitchboardProviderState.READY, "freshness confirmed");
        }
    }

    @Override
    public void onResyncRequired(String reasonCode, long currentSnapshotVersion) {
        lastRemoteContact = config.clock().instant();
        transport.requestResync(lastAppliedVersion(), reasonCode);
    }

    @Override
    public void onCredentialRevoked() {
        transition(SwitchboardProviderState.ERROR, "service credential revoked");
    }

    @Override
    public void onDisconnected(Throwable cause) {
        SwitchboardProviderState current = state.get();
        if (current == SwitchboardProviderState.CLOSED || current == SwitchboardProviderState.ERROR) {
            return;
        }
        if (snapshots.current().isPresent()) {
            transition(SwitchboardProviderState.READY_STALE, "distribution stream disconnected");
        } else {
            transition(SwitchboardProviderState.NOT_READY, "distribution stream disconnected without an active snapshot");
        }
    }

    void refreshFreshness() {
        Instant contact = lastRemoteContact;
        if (state.get() == SwitchboardProviderState.READY
                && contact != null
                && contact.plus(config.staleAfter()).isBefore(config.clock().instant())) {
            transition(SwitchboardProviderState.READY_STALE, "distribution freshness threshold exceeded");
        }
    }

    private void rejectIntegrity(SdkSnapshot candidate, String reason, String detail) {
        rejectCandidate(candidate.snapshotVersion(), reason, detail);
    }

    private void rejectCandidate(long snapshotVersion, String reason, String detail) {
        if (snapshots.current().isEmpty()) {
            transition(SwitchboardProviderState.ERROR, detail);
        }
        transport.reject(snapshotVersion, reason, detail);
    }

    private <T> ProviderEvaluation<T> evaluate(
            String key,
            T defaultValue,
            ValueType requestedType,
            EvaluationContext context,
            ValueConverter<T> converter) {
        return telemetry.observeEvaluation(requestedType.name(), () ->
                evaluateLocal(key, defaultValue, requestedType, context, converter));
    }

    private <T> ProviderEvaluation<T> evaluateLocal(
            String key,
            T defaultValue,
            ValueType requestedType,
            EvaluationContext context,
            ValueConverter<T> converter) {
        Optional<SdkSnapshot> active = snapshots.current();
        if (active.isEmpty() || state.get() == SwitchboardProviderState.CLOSED) {
            return error(defaultValue, ErrorCode.PROVIDER_NOT_READY, "no valid snapshot is active");
        }
        SdkSnapshot snapshot = active.orElseThrow();
        Optional<FlagDefinition> flag = snapshot.flag(key);
        if (flag.isEmpty()) {
            return error(defaultValue, ErrorCode.FLAG_NOT_FOUND, "flag not found: " + key);
        }
        EvaluationResult result = evaluator.evaluate(flag.orElseThrow(), requestedType, mapContext(context));
        if (result.hasError()) {
            return error(
                    defaultValue,
                    mapError(result.errorCode().orElseThrow()),
                    result.errorMessage().orElse("evaluation failed"));
        }
        try {
            T value = converter.convert(result.value().orElseThrow());
            return ProviderEvaluation.<T>builder()
                    .value(value)
                    .variant(result.variantKey().orElse(null))
                    .reason(reason(result))
                    .flagMetadata(metadata(snapshot, result))
                    .build();
        } catch (TypeConversionException exception) {
            return error(defaultValue, ErrorCode.TYPE_MISMATCH, exception.getMessage());
        }
    }

    private io.github.dlsrnjs125.switchboard.evaluation.EvaluationContext mapContext(
            EvaluationContext context) {
        if (context == null) {
            return io.github.dlsrnjs125.switchboard.evaluation.EvaluationContext.empty();
        }
        Map<String, Object> attributes = new LinkedHashMap<>(context.asObjectMap());
        attributes.remove(EvaluationContext.TARGETING_KEY);
        return new io.github.dlsrnjs125.switchboard.evaluation.EvaluationContext(
                context.getTargetingKey(), attributes);
    }

    private String reason(EvaluationResult result) {
        if (state.get() == SwitchboardProviderState.READY_STALE
                || state.get() == SwitchboardProviderState.ERROR) {
            return Reason.CACHED.name();
        }
        return switch (result.reason()) {
            case DISABLED -> Reason.DISABLED.name();
            case TARGETING_MATCH -> Reason.TARGETING_MATCH.name();
            case SPLIT -> Reason.SPLIT.name();
            case DEFAULT -> Reason.DEFAULT.name();
            case ERROR -> Reason.ERROR.name();
        };
    }

    private ImmutableMetadata metadata(SdkSnapshot snapshot, EvaluationResult result) {
        return ImmutableMetadata.builder()
                .addLong("snapshotVersion", snapshot.snapshotVersion())
                .addString("snapshotChecksum", snapshot.checksum())
                .addString("providerState", state.get().name())
                .addBoolean("stale", state.get() != SwitchboardProviderState.READY)
                .addString("evaluationReason", result.reason().name())
                .build();
    }

    private <T> ProviderEvaluation<T> error(T defaultValue, ErrorCode code, String message) {
        return ProviderEvaluation.<T>builder()
                .value(defaultValue)
                .reason(Reason.ERROR.name())
                .errorCode(code)
                .errorMessage(message)
                .flagMetadata(ImmutableMetadata.builder()
                        .addString("providerState", state.get().name())
                        .addBoolean("stale", snapshots.current().isPresent())
                        .build())
                .build();
    }

    private ErrorCode mapError(EvaluationErrorCode code) {
        return switch (code) {
            case TYPE_MISMATCH -> ErrorCode.TYPE_MISMATCH;
            case INVALID_CONTEXT -> ErrorCode.INVALID_CONTEXT;
            case TARGETING_KEY_MISSING -> ErrorCode.TARGETING_KEY_MISSING;
        };
    }

    private void transition(SwitchboardProviderState next, String message) {
        SwitchboardProviderState previous = state.getAndSet(next);
        if (previous == next) {
            return;
        }
        telemetry.stateChanged(previous, next);
        ProviderEventDetails details = ProviderEventDetails.builder().message(message).build();
        switch (next) {
            case READY -> emitProviderReady(details);
            case READY_STALE -> emitProviderStale(details);
            case NOT_READY, ERROR -> emitProviderError(ProviderEventDetails.builder()
                    .message(message)
                    .errorCode(next == SwitchboardProviderState.NOT_READY
                            ? ErrorCode.PROVIDER_NOT_READY : ErrorCode.GENERAL)
                    .build());
            case INITIALIZING, CLOSED -> {
                // Lifecycle-only states have no direct OpenFeature provider event.
            }
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface ValueConverter<T> {
        T convert(FlagValue value);
    }

    private static final class TypeConversionException extends RuntimeException {
        private TypeConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
