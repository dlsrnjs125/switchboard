package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.PublishResult;
import io.github.dlsrnjs125.switchboard.controlplane.observability.OutboxTelemetry;
import io.github.dlsrnjs125.switchboard.controlplane.publication.KafkaSnapshotEventPublisher;
import io.github.dlsrnjs125.switchboard.controlplane.publication.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("phase9")
@Tag("phase9-recovery-control")
class Phase9DependencyRecoveryEvidenceTest extends PostgresIntegrationSupport {
    private static final int MEASUREMENT_ITERATIONS = 30;
    private static final long MAX_LOCAL_RECOVERY_P95_MICROS = TimeUnit.SECONDS.toMicros(5);
    private static final long MAX_OUTBOX_RECOVERY_P95_MICROS = TimeUnit.SECONDS.toMicros(60);
    private static final long MAX_OUTBOX_RECOVERY_MICROS = TimeUnit.MINUTES.toMicros(5);
    private static final Duration OUTBOX_POLL_INTERVAL = Duration.ofMillis(25);
    private static final Duration OUTBOX_RECOVERY_TIMEOUT = Duration.ofSeconds(15);
    private static final String TOPIC = "switchboard.snapshot-published.v1.phase9-recovery";
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.3.1"));

    @BeforeAll
    static void startKafka() throws Exception {
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void recordsDependencyRecoveryDistributions() throws Exception {
        Map<String, Object> scenarios = new LinkedHashMap<>();
        resetDatabase();
        scenarios.put("postgresPreCommit", measurePostgresPreCommitRecovery());
        resetDatabase();
        scenarios.put("postgresPostCommit", measurePostgresPostCommitReconciliation());
        resetDatabase();
        scenarios.put("kafkaOutbox", measureKafkaOutboxRecovery());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-RCV-001");
        result.put("workloadResult", "pass");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("postgresFaultProxyImage", "ghcr.io/shopify/toxiproxy:2.12.0");
        result.put("kafkaImage", "apache/kafka:4.3.1");
        result.put("measurementIterations", MEASUREMENT_ITERATIONS);
        result.put("maximumLocalRecoveryP95Micros", MAX_LOCAL_RECOVERY_P95_MICROS);
        result.put("maximumOutboxRecoveryP95Micros", MAX_OUTBOX_RECOVERY_P95_MICROS);
        result.put("maximumOutboxRecoveryMicros", MAX_OUTBOX_RECOVERY_MICROS);
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.recovery.control.result",
                "build/reports/phase-09/control-plane-recovery.json"));
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private Map<String, Object> measurePostgresPreCommitRecovery() {
        FlagRevision revision = createBaselineAndRevision();
        long[] recoveryNanos = new long[MEASUREMENT_ITERATIONS];
        long expectedVersion = 0;
        for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
            long beforeFailure = expectedVersion;
            boolean enabled = sample % 2 == 0;
            POSTGRES_PROXY.setConnectionCut(true);
            try {
                assertThrows(RuntimeException.class,
                        () -> publish(revision, beforeFailure, enabled));
            } finally {
                POSTGRES_PROXY.setConnectionCut(false);
            }
            assertEquals(beforeFailure, currentVersion());

            long started = System.nanoTime();
            PublishResult recovered = publish(revision, expectedVersion, enabled);
            assertEquals(expectedVersion + 1, recovered.snapshotVersion());
            assertCommittedState(recovered.snapshotVersion());
            recoveryNanos[sample] = System.nanoTime() - started;
            expectedVersion = recovered.snapshotVersion();
        }

        Map<String, Long> percentiles = percentilesMicros(recoveryNanos);
        assertTrue(percentiles.get("p95") <= MAX_LOCAL_RECOVERY_P95_MICROS);
        return scenario(
                "PostgreSQL network is restored -> next publication commits and all atomic records converge",
                percentiles,
                Map.of(
                        "failedAttempts", MEASUREMENT_ITERATIONS,
                        "recoveredPublications", MEASUREMENT_ITERATIONS,
                        "finalSnapshotVersion", expectedVersion,
                        "residueAfterFailure", 0));
    }

    private Map<String, Object> measurePostgresPostCommitReconciliation() {
        FlagRevision revision = createBaselineAndRevision();
        long[] recoveryNanos = new long[MEASUREMENT_ITERATIONS];
        long expectedVersion = 0;
        for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
            long versionBeforeCommit = expectedVersion;
            boolean enabled = sample % 2 == 0;
            UUID correlationId = UUID.randomUUID();
            IllegalStateException ambiguous = assertThrows(IllegalStateException.class, () ->
                    inTransaction(status -> {
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCommit() {
                                        throw new IllegalStateException(
                                                "injected response loss after server commit");
                                    }
                                });
                        return service.publish(
                                "acme", "checkout", "prod", "alice",
                                new Publish(
                                        "feature-a",
                                        revision.revisionNumber(),
                                        enabled,
                                        versionBeforeCommit,
                                        correlationId));
                    }));
            assertEquals("injected response loss after server commit", ambiguous.getMessage());

            long started = System.nanoTime();
            var reconciled = inTransaction(status -> service.currentSnapshot(
                    "acme", "checkout", "prod", "alice"));
            assertEquals(versionBeforeCommit + 1, reconciled.snapshotVersion());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_events WHERE correlation_id = ?",
                    Integer.class,
                    correlationId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE snapshot_id = ?",
                    Integer.class,
                    reconciled.snapshotId()));
            recoveryNanos[sample] = System.nanoTime() - started;

            DomainException blindRetry = assertThrows(DomainException.class, () ->
                    inTransaction(status -> service.publish(
                            "acme", "checkout", "prod", "alice",
                            new Publish(
                                    "feature-a",
                                    revision.revisionNumber(),
                                    enabled,
                                    versionBeforeCommit,
                                    correlationId))));
            assertEquals("ENVIRONMENT_VERSION_CONFLICT", blindRetry.code());
            expectedVersion = reconciled.snapshotVersion();
        }

        Map<String, Long> percentiles = percentilesMicros(recoveryNanos);
        assertTrue(percentiles.get("p95") <= MAX_LOCAL_RECOVERY_P95_MICROS);
        return scenario(
                "ambiguous committed response -> authoritative Snapshot/audit/outbox reconciliation",
                percentiles,
                Map.of(
                        "ambiguousCommits", MEASUREMENT_ITERATIONS,
                        "reconciledCommits", MEASUREMENT_ITERATIONS,
                        "blindRetriesRejected", MEASUREMENT_ITERATIONS,
                        "finalSnapshotVersion", expectedVersion));
    }

    private Map<String, Object> measureKafkaOutboxRecovery() {
        FlagRevision revision = createBaselineAndRevision();
        Map<String, Object> producerProperties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.RETRIES_CONFIG, 0);
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(producerProperties);
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(factory);
        Clock recoveryClock = Clock.systemUTC();
        OutboxRelay relay = new OutboxRelay(
                repository,
                new KafkaSnapshotEventPublisher(kafka, TOPIC, Duration.ofSeconds(3)),
                recoveryClock,
                transaction.getTransactionManager(),
                new OutboxTelemetry(new SimpleMeterRegistry(), ObservationRegistry.NOOP));
        long[] recoveryNanos = new long[MEASUREMENT_ITERATIONS];
        long expectedVersion = 0;
        boolean kafkaPaused = false;
        try {
            for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
                PublishResult publication = publish(revision, expectedVersion, sample % 2 == 0);
                expectedVersion = publication.snapshotVersion();
                UUID eventId = jdbc.queryForObject(
                        "SELECT id FROM outbox_events WHERE snapshot_id = ?", UUID.class, publication.snapshotId());

                KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
                kafkaPaused = true;
                try {
                    relay.relay();
                } finally {
                    KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
                    kafkaPaused = false;
                }
                long started = System.nanoTime();
                assertEquals(1, jdbc.queryForObject(
                        "SELECT attempt_count FROM outbox_events WHERE id = ?", Integer.class, eventId));
                assertNull(jdbc.queryForObject(
                        "SELECT published_at FROM outbox_events WHERE id = ?", Object.class, eventId));
                Instant nextAttemptAt = jdbc.queryForObject(
                        "SELECT next_attempt_at FROM outbox_events WHERE id = ?",
                        Timestamp.class,
                        eventId).toInstant();
                assertTrue(nextAttemptAt.isAfter(recoveryClock.instant()),
                        "failed delivery must retain the application's future retry schedule");

                awaitOutboxPublished(relay, eventId, OUTBOX_RECOVERY_TIMEOUT);
                recoveryNanos[sample] = System.nanoTime() - started;
                assertEquals(2, jdbc.queryForObject(
                        "SELECT attempt_count FROM outbox_events WHERE id = ?", Integer.class, eventId));
            }
        } finally {
            if (kafkaPaused) {
                KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
            }
            factory.destroy();
        }

        Map<String, Long> percentiles = percentilesMicros(recoveryNanos);
        assertTrue(percentiles.get("p95") <= MAX_OUTBOX_RECOVERY_P95_MICROS);
        assertTrue(percentiles.get("max") <= MAX_OUTBOX_RECOVERY_MICROS);
        assertEquals(MEASUREMENT_ITERATIONS, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL", Integer.class));
        return scenario(
                "Kafka container unpaused -> persisted next_attempt_at becomes eligible -> broker ACK and published_at persisted",
                percentiles,
                Map.of(
                        "failedDeliveryAttempts", MEASUREMENT_ITERATIONS,
                        "recoveredEvents", MEASUREMENT_ITERATIONS,
                        "unpublishedEventsAfterRecovery", 0,
                        "stableEventIds", MEASUREMENT_ITERATIONS,
                        "retrySchedulePreserved", MEASUREMENT_ITERATIONS,
                        "applicationRetryStateMutations", 0,
                        "relayPollIntervalMillis", OUTBOX_POLL_INTERVAL.toMillis()));
    }

    private void awaitOutboxPublished(OutboxRelay relay, UUID eventId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Object publishedAt = null;
        while (publishedAt == null && System.nanoTime() < deadline) {
            relay.relay();
            publishedAt = jdbc.queryForObject(
                    "SELECT published_at FROM outbox_events WHERE id = ?", Object.class, eventId);
            if (publishedAt == null) {
                try {
                    Thread.sleep(OUTBOX_POLL_INTERVAL.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting outbox recovery", exception);
                }
            }
        }
        assertNotNull(publishedAt, "outbox event did not recover through its persisted retry schedule");
    }

    private FlagRevision createBaselineAndRevision() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject(
                "acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "acme", "checkout", "alice",
                new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("feature-a", io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType.BOOLEAN)));
        return inTransaction(status -> service.createDraftRevision(
                "acme", "checkout", "feature-a", "alice",
                new CreateRevision(
                        List.of(
                                new Variant("off", JsonNodeFactory.instance.booleanNode(false)),
                                new Variant("on", JsonNodeFactory.instance.booleanNode(true))),
                        "off",
                        "phase9-recovery-seed",
                        List.of())));
    }

    private PublishResult publish(FlagRevision revision, long expectedVersion, boolean enabled) {
        return inTransaction(status -> service.publish(
                "acme", "checkout", "prod", "alice",
                new Publish(
                        "feature-a",
                        revision.revisionNumber(),
                        enabled,
                        expectedVersion,
                        UUID.randomUUID())));
    }

    private long currentVersion() {
        return jdbc.queryForObject(
                "SELECT current_snapshot_version FROM environments WHERE environment_key = 'prod'",
                Long.class);
    }

    private void assertCommittedState(long version) {
        assertEquals(version, currentVersion());
        assertEquals(version, jdbc.queryForObject(
                "SELECT count(*) FROM configuration_snapshots", Long.class));
        assertEquals(version, jdbc.queryForObject("SELECT count(*) FROM audit_events", Long.class));
        assertEquals(version, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class));
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
}
