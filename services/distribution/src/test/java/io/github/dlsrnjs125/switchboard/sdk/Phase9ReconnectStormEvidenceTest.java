package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import io.github.dlsrnjs125.switchboard.distribution.grpc.GrpcServerLifecycle;
import io.github.dlsrnjs125.switchboard.distribution.grpc.SessionRegistry;
import io.github.dlsrnjs125.switchboard.distribution.grpc.SnapshotDistributionGrpcService;
import io.github.dlsrnjs125.switchboard.distribution.observability.DistributionTelemetry;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("phase9")
@Tag("phase9-reconnect")
class Phase9ReconnectStormEvidenceTest extends DistributionPostgresSupport {
    private static final int[] CLIENT_COUNTS = clientCounts();
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAXIMUM_BACKOFF = Duration.ofSeconds(5);
    private static final double JITTER = 0.4;
    private static final Duration DEADLINE = Duration.ofMinutes(5);
    private static final int DATABASE_POOL_SIZE = 16;

    @Test
    void recordsReconnectStormRecoveryAndDatabaseAmplification() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        try (HikariDataSource pooledDataSource = pooledDataSource()) {
            jdbc = new org.springframework.jdbc.core.JdbcTemplate(pooledDataSource);
            CountingRepository countingRepository = new CountingRepository(pooledDataSource);
            repository = countingRepository;
            long version = 1;
            for (int clients : CLIENT_COUNTS) {
                scenarios.add(runScenario(countingRepository, clients, version));
                version += 2;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-RCN-001");
        result.put("status", "candidate");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("vm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("databasePoolSize", DATABASE_POOL_SIZE);
        result.put("initialBackoffMillis", INITIAL_BACKOFF.toMillis());
        result.put("maximumBackoffMillis", MAXIMUM_BACKOFF.toMillis());
        result.put("jitterFraction", JITTER);
        result.put("topology", "single shared Netty channel, one logical stream per client, restarted Distribution server");
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.reconnect.result", "build/reports/phase-09/reconnect-storm.json"));
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private HikariDataSource pooledDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(DATABASE_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(DEADLINE.toMillis());
        config.setPoolName("phase9-reconnect");
        return new HikariDataSource(config);
    }

    private Map<String, Object> runScenario(
            CountingRepository countingRepository, int clients, long initialVersion) throws Exception {
        insertSnapshot(initialVersion);
        CountDownLatch initialApplied = new CountDownLatch(clients);
        CountDownLatch disconnected = new CountDownLatch(clients);
        CountDownLatch recovered = new CountDownLatch(clients);
        CountDownLatch acknowledged = new CountDownLatch(clients);
        AtomicLongArray recoveryMicros = new AtomicLongArray(clients);
        AtomicLongArray firstBackoffMillis = new AtomicLongArray(clients);
        AtomicInteger streamErrors = new AtomicInteger();
        List<String> unexpected = new CopyOnWriteArrayList<>();
        Set<String> acceptedDeliveries = ConcurrentHashMap.newKeySet();

        countingRepository.resetCounts();
        ServerFixture initial = startServer(0, clients, initialVersion + 1, acknowledged, acceptedDeliveries);
        int port = initial.server().port();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.min(32, Math.max(4, Runtime.getRuntime().availableProcessors())));
        List<GrpcSnapshotTransport> transports = new ArrayList<>(clients);
        AtomicLong outageStarted = new AtomicLong();
        try {
            for (int index = 0; index < clients; index++) {
                int clientIndex = index;
                double random = deterministicRandom(clientIndex);
                firstBackoffMillis.set(clientIndex, jitteredDelayMillis(random));
                SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                        "localhost:" + port,
                        bearer(),
                        "orders",
                        "checkout",
                        "production",
                        Path.of("build", "phase-09", "unused-lkg-" + clients + "-" + clientIndex),
                        Duration.ofSeconds(30),
                        Duration.ofDays(7),
                        INITIAL_BACKOFF,
                        MAXIMUM_BACKOFF,
                        JITTER,
                        java.time.Clock.systemUTC());
                GrpcSnapshotTransport transport = new GrpcSnapshotTransport(
                        config,
                        channel,
                        scheduler,
                        () -> random,
                        new SwitchboardProviderTelemetry(
                                new SimpleMeterRegistry(), ObservationRegistry.NOOP, config.clock()));
                AtomicLong appliedVersion = new AtomicLong();
                AtomicBoolean disconnectObserved = new AtomicBoolean();
                transport.start(appliedVersion::get, new SnapshotTransport.Listener() {
                    @Override
                    public void onConnected() {
                        // A full Snapshot or heartbeat proves the stream is serving.
                    }

                    @Override
                    public void onSnapshot(FullSnapshot snapshot) {
                        long previous = appliedVersion.getAndSet(snapshot.getSnapshotVersion());
                        if (snapshot.getSnapshotVersion() == initialVersion && previous < initialVersion) {
                            initialApplied.countDown();
                        } else if (snapshot.getSnapshotVersion() == initialVersion + 1
                                && previous < initialVersion + 1) {
                            recoveryMicros.set(clientIndex, TimeUnit.NANOSECONDS.toMicros(
                                    System.nanoTime() - outageStarted.get()));
                            transport.acknowledge(
                                    snapshot.getDeliveryId(), snapshot.getSnapshotVersion(), snapshot.getChecksum());
                            recovered.countDown();
                        }
                    }

                    @Override
                    public void onHeartbeat(long currentSnapshotVersion) {
                        if (currentSnapshotVersion < appliedVersion.get()) {
                            unexpected.add("REGRESSIVE_HEARTBEAT:" + clientIndex);
                        }
                    }

                    @Override
                    public void onResyncRequired(String reasonCode, long currentSnapshotVersion) {
                        unexpected.add("RESYNC_REQUIRED:" + reasonCode);
                    }

                    @Override
                    public void onCredentialRevoked() {
                        unexpected.add("CREDENTIAL_REVOKED:" + clientIndex);
                    }

                    @Override
                    public void onDisconnected(Throwable cause) {
                        if (disconnectObserved.compareAndSet(false, true)) {
                            disconnected.countDown();
                        }
                        if (cause != null) {
                            streamErrors.incrementAndGet();
                        }
                    }
                });
                transports.add(transport);
            }

            assertTrue(initialApplied.await(DEADLINE.toSeconds(), TimeUnit.SECONDS),
                    "initial Snapshot deadline exceeded");
            assertTrue(unexpected.isEmpty(), () -> "initial stream errors: " + unexpected);
            long initialAuthenticationQueries = countingRepository.authenticationQueries();
            long initialBootstrapQueries = countingRepository.bootstrapQueries();
            assertTrue(initialAuthenticationQueries >= clients
                            && initialAuthenticationQueries <= clients * 3L,
                    "initial authentication amplification exceeded 3x");
            assertTrue(initialBootstrapQueries >= clients
                            && initialBootstrapQueries <= clients * 2L,
                    "initial Snapshot bootstrap amplification exceeded 2x");

            // Seed the next authoritative version without notifying the running coordinator.
            // Doing this before the outage keeps fixture setup out of the recovery timer and
            // avoids competing with in-flight authentication calls while the server drains.
            insertSnapshot(initialVersion + 1);
            countingRepository.resetCounts();
            outageStarted.set(System.nanoTime());
            initial.server().stop();
            assertTrue(disconnected.await(DEADLINE.toSeconds(), TimeUnit.SECONDS),
                    "disconnect observation deadline exceeded");
            ServerFixture restarted =
                    startServer(port, clients, initialVersion + 1, acknowledged, acceptedDeliveries);
            try {
                assertTrue(recovered.await(DEADLINE.toSeconds(), TimeUnit.SECONDS),
                        "reconnect recovery deadline exceeded");
                assertTrue(acknowledged.await(DEADLINE.toSeconds(), TimeUnit.SECONDS),
                        () -> "reconnect ACK deadline exceeded: received "
                                + acceptedDeliveries.size() + " of " + clients + " unique delivery IDs");
                assertEquals(clients, acceptedDeliveries.size(),
                        "every recovered delivery must have one accepted ACK identity");
                assertTrue(unexpected.isEmpty(), () -> "reconnect workload errors: " + unexpected);

                long reconnectAuthenticationQueries = countingRepository.authenticationQueries();
                long reconnectBootstrapQueries = countingRepository.bootstrapQueries();
                assertTrue(reconnectAuthenticationQueries >= clients * 2L
                                && reconnectAuthenticationQueries <= clients * 4L,
                        "reconnect Subscribe plus ACK authentication amplification exceeded 4x");
                assertTrue(reconnectBootstrapQueries >= clients
                                && reconnectBootstrapQueries <= clients * 2L,
                        "reconnect Snapshot bootstrap amplification exceeded 2x");
                long admissionRejected = rejectedAdmissions(restarted.meters());
                assertEquals(0, admissionRejected, "reconnect admission must not reject a measured client");

                Map<String, Object> scenario = new LinkedHashMap<>();
                scenario.put("clients", clients);
                scenario.put("admittedSessions", clients);
                scenario.put("admissionRejected", admissionRejected);
                scenario.put("firstBackoffDelayMillis", percentiles(firstBackoffMillis));
                scenario.put("outageToSnapshotRecoveryMicros", percentiles(recoveryMicros));
                scenario.put("initialAuthenticationQueries", initialAuthenticationQueries);
                scenario.put("initialBootstrapQueries", initialBootstrapQueries);
                scenario.put("initialAuthenticationQueriesPerClient",
                        ratio(initialAuthenticationQueries, clients));
                scenario.put("initialBootstrapQueriesPerClient",
                        ratio(initialBootstrapQueries, clients));
                scenario.put("reconnectAuthenticationQueries", reconnectAuthenticationQueries);
                scenario.put("reconnectBootstrapQueries", reconnectBootstrapQueries);
                scenario.put("authenticationQueriesPerRecoveredClient",
                        ratio(reconnectAuthenticationQueries, clients));
                scenario.put("estimatedReconnectSubscribeAuthenticationQueries",
                        reconnectAuthenticationQueries - clients);
                scenario.put("estimatedReconnectSubscribeAuthenticationQueriesPerClient",
                        ratio(reconnectAuthenticationQueries - clients, clients));
                scenario.put("bootstrapQueriesPerRecoveredClient", ratio(reconnectBootstrapQueries, clients));
                scenario.put("disconnectSignals", clients);
                scenario.put("streamErrorCallbacks", streamErrors.get());
                scenario.put("recoveredClients", clients);
                scenario.put("serverAcceptedAcks", acceptedDeliveries.size());
                scenario.put("errors", unexpected.size());
                return scenario;
            } finally {
                restarted.server().stop();
            }
        } finally {
            channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
            scheduler.shutdownNow();
        }
    }

    private ServerFixture startServer(
            int port,
            int clients,
            long acknowledgedVersion,
            CountDownLatch acknowledged,
            Set<String> acceptedDeliveries) {
        SnapshotCache cache = new SnapshotCache();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DistributionTelemetry telemetry = new DistributionTelemetry(meters, ObservationRegistry.NOOP);
        SessionRegistry sessions = new SessionRegistry(repository, cache, clock, clients + 10, telemetry);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions),
                telemetry);
        SnapshotDistributionGrpcService delegate =
                new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(coordinator, sessions, clock) {
            @Override
            public void acknowledge(AckRequest request, StreamObserver<AckResponse> responseObserver) {
                delegate.acknowledge(request, new StreamObserver<>() {
                    @Override
                    public void onNext(AckResponse response) {
                        if (response.getAccepted()
                                && request.getSnapshotVersion() == acknowledgedVersion
                                && acceptedDeliveries.add(request.getDeliveryId())) {
                            acknowledged.countDown();
                        }
                        responseObserver.onNext(response);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        responseObserver.onError(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onCompleted();
                    }
                });
            }
        };
        GrpcServerLifecycle server = new GrpcServerLifecycle(
                service, new CredentialServerInterceptor(repository), sessions, port);
        server.start();
        return new ServerFixture(server, meters);
    }

    private long rejectedAdmissions(SimpleMeterRegistry meters) {
        var counter = meters.find("switchboard.distribution.admission.total")
                .tag("outcome", "rejected")
                .counter();
        return counter == null ? 0 : Math.round(counter.count());
    }

    private double deterministicRandom(int clientIndex) {
        long mixed = (clientIndex * 1_103_515_245L + 12_345L) & 0x7fff_ffffL;
        return mixed / (double) 0x8000_0000L;
    }

    private static int[] clientCounts() {
        String configured = System.getenv("SWITCHBOARD_PHASE9_RECONNECT_CLIENTS");
        if (configured == null || configured.isBlank()) {
            return new int[] {100, 500, 1_000};
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private long jitteredDelayMillis(double random) {
        double factor = 1 - JITTER + (2 * JITTER * random);
        return Math.max(1, (long) (INITIAL_BACKOFF.toMillis() * factor));
    }

    private Map<String, Long> percentiles(AtomicLongArray values) {
        long[] sorted = new long[values.length()];
        for (int index = 0; index < values.length(); index++) {
            sorted[index] = values.get(index);
        }
        Arrays.sort(sorted);
        assertTrue(sorted[0] > 0, "every client must produce a timing sample");
        return Map.of(
                "p50", percentile(sorted, 0.50),
                "p95", percentile(sorted, 0.95),
                "p99", percentile(sorted, 0.99),
                "max", sorted[sorted.length - 1]);
    }

    private long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private double ratio(long value, int clients) {
        return Math.round((value / (double) clients) * 1_000.0) / 1_000.0;
    }

    private final class CountingRepository extends DistributionRepository {
        private final AtomicLong authenticationQueries = new AtomicLong();
        private final AtomicLong bootstrapQueries = new AtomicLong();

        private CountingRepository(DataSource dataSource) {
            super(new NamedParameterJdbcTemplate(dataSource), objectMapper, passwordEncoder, clock);
        }

        @Override
        public Optional<CredentialPrincipal> authenticate(UUID credentialId, String secret) {
            authenticationQueries.incrementAndGet();
            return super.authenticate(credentialId, secret);
        }

        @Override
        public Optional<SnapshotArtifact> loadCurrentSnapshot(EnvironmentScope scope) {
            bootstrapQueries.incrementAndGet();
            return super.loadCurrentSnapshot(scope);
        }

        private void resetCounts() {
            authenticationQueries.set(0);
            bootstrapQueries.set(0);
        }

        private long authenticationQueries() {
            return authenticationQueries.get();
        }

        private long bootstrapQueries() {
            return bootstrapQueries.get();
        }
    }

    private record ServerFixture(GrpcServerLifecycle server, SimpleMeterRegistry meters) {
    }
}
