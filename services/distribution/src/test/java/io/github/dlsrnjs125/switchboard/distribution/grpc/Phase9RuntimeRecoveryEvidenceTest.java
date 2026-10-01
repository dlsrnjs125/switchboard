package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfeature.sdk.ImmutableContext;
import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("phase9")
@Tag("phase9-recovery-runtime")
class Phase9RuntimeRecoveryEvidenceTest extends DistributionPostgresSupport {
    private static final int MEASUREMENT_ITERATIONS = 30;
    private static final long MAX_RECOVERY_P95_MICROS = TimeUnit.SECONDS.toMicros(5);

    @TempDir
    Path temporaryDirectory;

    private GrpcServerLifecycle server;
    private SessionRegistry sessions;
    private SwitchboardProvider provider;

    @AfterEach
    void closeResources() {
        closeScenario();
    }

    @Test
    void recordsProcessAndCredentialDependencyRecoveryDistributions() throws Exception {
        Map<String, Object> scenarios = new LinkedHashMap<>();
        insertSnapshot(3);
        scenarios.put("distributionRestart", measureDistributionRestartRecovery());

        closeScenario();
        resetDatabase();
        insertSnapshot(3);
        scenarios.put("credentialDependency", measureCredentialDependencyRecovery());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-RCV-001");
        result.put("workloadResult", "pass");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("measurementIterations", MEASUREMENT_ITERATIONS);
        result.put("maximumRecoveryP95Micros", MAX_RECOVERY_P95_MICROS);
        result.put("providerReconnectBackoffMillis", Map.of("initial", 10, "maximum", 100, "jitter", 0));
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.recovery.runtime.result",
                "build/reports/phase-09/runtime-recovery.json"));
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private Map<String, Object> measureDistributionRestartRecovery() throws Exception {
        startServer(0, repository);
        int port = server.port();
        startProvider(port, "distribution-restart-lkg.json");
        long[] recoveryNanos = new long[MEASUREMENT_ITERATIONS];

        for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
            server.stop();
            server = null;
            awaitState(SwitchboardProviderState.READY_STALE, Duration.ofSeconds(10));
            assertProviderConverged();

            startServer(port, repository);
            long started = System.nanoTime();
            awaitState(SwitchboardProviderState.READY, Duration.ofSeconds(10));
            recoveryNanos[sample] = System.nanoTime() - started;
            assertProviderConverged();
        }

        Map<String, Long> percentiles = percentilesMicros(recoveryNanos);
        assertTrue(percentiles.get("p95") <= MAX_RECOVERY_P95_MICROS);
        return scenario(
                "gRPC server reports ready after restart -> Provider READY with authoritative Snapshot",
                percentiles,
                Map.of(
                        "restartCycles", MEASUREMENT_ITERATIONS,
                        "readyStaleTransitions", MEASUREMENT_ITERATIONS,
                        "readyRecoveries", MEASUREMENT_ITERATIONS,
                        "finalSnapshotVersion", provider.lastAppliedVersion()));
    }

    private Map<String, Object> measureCredentialDependencyRecovery() throws Exception {
        FaultInjectingDistributionRepository faultRepository =
                new FaultInjectingDistributionRepository();
        startServer(0, faultRepository);
        int port = server.port();
        startProvider(port, "credential-dependency-lkg.json");
        long[] recoveryNanos = new long[MEASUREMENT_ITERATIONS];
        int[] failuresPerCycle = new int[MEASUREMENT_ITERATIONS];
        int faultedCycles = 0;
        int recoveredStreams = 0;

        for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
            server.stop();
            server = null;
            awaitState(SwitchboardProviderState.READY_STALE, Duration.ofSeconds(10));
            assertProviderConverged();

            int failuresBeforeFault = faultRepository.authenticationFailures();
            faultRepository.unavailable(true);
            startServer(port, faultRepository);
            awaitAuthenticationFailure(faultRepository, failuresBeforeFault, Duration.ofSeconds(10));
            assertEquals(SwitchboardProviderState.READY_STALE, provider.switchboardState());
            assertProviderConverged();

            int failuresThisCycle = faultRepository.authenticationFailures() - failuresBeforeFault;
            assertTrue(failuresThisCycle >= 1, "every cycle must observe its own credential failure");
            failuresPerCycle[sample] = failuresThisCycle;
            faultedCycles++;

            long started = System.nanoTime();
            faultRepository.unavailable(false);
            awaitState(SwitchboardProviderState.READY, Duration.ofSeconds(10));
            recoveryNanos[sample] = System.nanoTime() - started;
            assertProviderConverged();
            recoveredStreams++;
        }

        Map<String, Long> percentiles = percentilesMicros(recoveryNanos);
        assertTrue(percentiles.get("p95") <= MAX_RECOVERY_P95_MICROS);
        assertEquals(MEASUREMENT_ITERATIONS, faultedCycles);
        assertEquals(MEASUREMENT_ITERATIONS, recoveredStreams);
        assertTrue(Arrays.stream(failuresPerCycle).allMatch(count -> count >= 1));
        return scenario(
                "credential repository recovers -> retrying authenticated stream reaches Provider READY",
                percentiles,
                Map.of(
                        "injectedUnavailableResponses", faultRepository.authenticationFailures(),
                        "failuresPerCycle", Arrays.stream(failuresPerCycle).boxed().toList(),
                        "faultedCycles", faultedCycles,
                        "recoveredStreams", recoveredStreams,
                        "readyStaleTransitions", faultedCycles,
                        "finalSnapshotVersion", provider.lastAppliedVersion(),
                        "injectionBoundary", "DistributionRepository.authenticate"));
    }

    private void startProvider(int port, String lkgFile) throws Exception {
        provider = new SwitchboardProvider(new SwitchboardProviderConfig(
                "localhost:" + port,
                bearer(),
                "orders",
                "checkout",
                "production",
                temporaryDirectory.resolve(lkgFile),
                Duration.ofSeconds(30),
                Duration.ofDays(7),
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                0,
                clock), new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        provider.initialize(ImmutableContext.EMPTY);
        awaitState(SwitchboardProviderState.READY, Duration.ofSeconds(10));
        assertProviderConverged();
    }

    private void startServer(int port, DistributionRepository activeRepository) {
        SnapshotCache cache = new SnapshotCache();
        sessions = new SessionRegistry(activeRepository, cache, clock, 100);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                activeRepository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions));
        SnapshotDistributionGrpcService service =
                new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        server = new GrpcServerLifecycle(
                service,
                new CredentialServerInterceptor(activeRepository),
                sessions,
                port);
        server.start();
    }

    private void awaitState(SwitchboardProviderState expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (provider.switchboardState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, provider.switchboardState());
    }

    private void awaitAuthenticationFailure(
            FaultInjectingDistributionRepository faultRepository,
            int previousFailures,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (faultRepository.authenticationFailures() <= previousFailures
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(faultRepository.authenticationFailures() > previousFailures);
    }

    private void assertProviderConverged() {
        assertEquals(3, provider.lastAppliedVersion());
        assertTrue(provider.getBooleanEvaluation(
                "checkout-v2", false, ImmutableContext.EMPTY).getValue());
    }

    private void closeScenario() {
        if (provider != null) {
            provider.shutdown();
            provider = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private Map<String, Object> scenario(
            String recoveryBoundary,
            Map<String, Long> recoveryLatencyMicros,
            Map<String, ?> outcome) {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("samples", MEASUREMENT_ITERATIONS);
        scenario.put("recoveryBoundary", recoveryBoundary);
        scenario.put("recoveryLatencyMicros", recoveryLatencyMicros);
        scenario.put("outcome", outcome);
        scenario.put("errors", 0);
        return scenario;
    }

    private Map<String, Long> percentilesMicros(long[] values) {
        long[] sorted = Arrays.stream(values).sorted().toArray();
        return Map.of(
                "p50", micros(sorted, 0.50),
                "p95", micros(sorted, 0.95),
                "p99", micros(sorted, 0.99),
                "max", TimeUnit.NANOSECONDS.toMicros(sorted[sorted.length - 1]));
    }

    private long micros(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return TimeUnit.NANOSECONDS.toMicros(sorted[index]);
    }

    private final class FaultInjectingDistributionRepository extends DistributionRepository {
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final AtomicInteger authenticationFailures = new AtomicInteger();

        private FaultInjectingDistributionRepository() {
            super(new NamedParameterJdbcTemplate(DATA_SOURCE), objectMapper, passwordEncoder, clock);
        }

        @Override
        public Optional<CredentialPrincipal> authenticate(UUID credentialId, String credentialSecret) {
            if (unavailable.get()) {
                authenticationFailures.incrementAndGet();
                throw new DataAccessResourceFailureException("injected credential repository outage");
            }
            return super.authenticate(credentialId, credentialSecret);
        }

        private void unavailable(boolean value) {
            unavailable.set(value);
        }

        private int authenticationFailures() {
            return authenticationFailures.get();
        }
    }
}
