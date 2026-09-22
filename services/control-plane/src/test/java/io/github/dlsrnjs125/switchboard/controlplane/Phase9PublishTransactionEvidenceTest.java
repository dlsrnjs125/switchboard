package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotValidator;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.infrastructure.UuidV7Generator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("phase9")
@Tag("phase9-publish")
class Phase9PublishTransactionEvidenceTest extends PostgresIntegrationSupport {
    private static final int[] FLAG_COUNTS = {1, 100, 1_000};
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 30;

    @Test
    void recordsTransactionOutboxCommitLatencyBySnapshotSize() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int flagCount : FLAG_COUNTS) {
            resetDatabase();
            scenarios.add(runScenario(flagCount));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-PUB-001");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("warmupIterations", WARMUP_ITERATIONS);
        result.put("measurementIterations", MEASUREMENT_ITERATIONS);
        result.put("arrivalModel", "single-threaded sequential publication");
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.publish.result",
                "build/reports/phase-09/publish-transaction.json"));
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private Map<String, Object> runScenario(int flagCount) {
        ObjectMapper json = new ObjectMapper();
        TimingSnapshotCompiler compiler = new TimingSnapshotCompiler(json);
        TimingSnapshotValidator validator = new TimingSnapshotValidator();
        service = new ControlPlaneService(
                repository, new UuidV7Generator(), clock, compiler, validator);
        createScope();
        seedPublishedFlags(flagCount);

        long expectedVersion = 1;
        for (int warmup = 0; warmup < WARMUP_ITERATIONS; warmup++) {
            expectedVersion = publish(expectedVersion).version();
        }
        compiler.clear();
        validator.clear();

        long[] transactionNanos = new long[MEASUREMENT_ITERATIONS];
        long[] persistenceAndCommitNanos = new long[MEASUREMENT_ITERATIONS];
        long[] snapshotBytes = new long[MEASUREMENT_ITERATIONS];
        for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
            long started = System.nanoTime();
            Publication publication = publish(expectedVersion);
            long elapsed = System.nanoTime() - started;
            expectedVersion = publication.version();
            transactionNanos[sample] = elapsed;
            persistenceAndCommitNanos[sample] = Math.max(
                    0, elapsed - compiler.lastNanos() - validator.lastNanos());
            snapshotBytes[sample] = jdbc.queryForObject(
                    "SELECT octet_length(payload::text) FROM configuration_snapshots WHERE id = ?",
                    Long.class,
                    publication.snapshotId());
        }

        assertEquals(MEASUREMENT_ITERATIONS, compiler.samples().length);
        assertEquals(MEASUREMENT_ITERATIONS, validator.samples().length);
        assertEquals(WARMUP_ITERATIONS + MEASUREMENT_ITERATIONS,
                jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        assertEquals(expectedVersion,
                jdbc.queryForObject("SELECT current_snapshot_version FROM environments", Long.class));

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("flags", flagCount);
        scenario.put("snapshotBytes", percentilesRaw(snapshotBytes));
        scenario.put("compileLatencyMicros", percentiles(compiler.samples()));
        scenario.put("validationLatencyMicros", percentiles(validator.samples()));
        scenario.put("persistenceAndCommitLatencyMicros", percentiles(persistenceAndCommitNanos));
        scenario.put("transactionAndOutboxCommitLatencyMicros", percentiles(transactionNanos));
        scenario.put("sequentialThroughputOpsPerSecond",
                MEASUREMENT_ITERATIONS * 1_000_000_000.0 / Arrays.stream(transactionNanos).sum());
        scenario.put("committedOutboxEvents", WARMUP_ITERATIONS + MEASUREMENT_ITERATIONS);
        return scenario;
    }

    private void createScope() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject(
                "acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "acme", "checkout", "alice",
                new CreateEnvironment("production", "Production", EnvironmentType.PRODUCTION)));
    }

    private void seedPublishedFlags(int flagCount) {
        UUID tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_key = 'acme'", UUID.class);
        UUID projectId = jdbc.queryForObject("SELECT id FROM projects WHERE project_key = 'checkout'", UUID.class);
        UUID environmentId = jdbc.queryForObject(
                "SELECT id FROM environments WHERE environment_key = 'production'", UUID.class);
        List<SeedFlag> flags = new ArrayList<>(flagCount);
        for (int index = 0; index < flagCount; index++) {
            flags.add(new SeedFlag(
                    namedUuid("flag-" + index),
                    namedUuid("revision-" + index),
                    "flag-" + index));
        }

        transaction.executeWithoutResult(status -> {
            batch("""
                    INSERT INTO feature_flags (
                        id, tenant_id, project_id, flag_key, value_type,
                        lifecycle_status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'BOOLEAN', 'ACTIVE', ?, ?)
                    """, flags, (statement, flag) -> {
                statement.setObject(1, flag.flagId());
                statement.setObject(2, tenantId);
                statement.setObject(3, projectId);
                statement.setString(4, flag.key());
                statement.setObject(5, java.sql.Timestamp.from(clock.instant()));
                statement.setObject(6, java.sql.Timestamp.from(clock.instant()));
            });
            batch("""
                    INSERT INTO flag_revisions (
                        id, tenant_id, project_id, feature_flag_id, revision_number,
                        value_type, lifecycle_state, default_variant_key, rollout_seed,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 1, 'BOOLEAN', 'DRAFT', 'off', ?, ?, ?)
                    """, flags, (statement, flag) -> {
                statement.setObject(1, flag.revisionId());
                statement.setObject(2, tenantId);
                statement.setObject(3, projectId);
                statement.setObject(4, flag.flagId());
                statement.setString(5, flag.key() + "-seed");
                statement.setObject(6, java.sql.Timestamp.from(clock.instant()));
                statement.setObject(7, java.sql.Timestamp.from(clock.instant()));
            });
            List<SeedVariant> variants = flags.stream()
                    .flatMap(flag -> java.util.stream.Stream.of(
                            new SeedVariant(flag.revisionId(), "off", false),
                            new SeedVariant(flag.revisionId(), "on", true)))
                    .toList();
            batch("""
                    INSERT INTO flag_variants (revision_id, variant_key, value_type, value)
                    VALUES (?, ?, 'BOOLEAN', CAST(? AS jsonb))
                    """, variants, (statement, variant) -> {
                statement.setObject(1, variant.revisionId());
                statement.setString(2, variant.key());
                statement.setString(3, Boolean.toString(variant.value()));
            });
            batch("""
                    UPDATE flag_revisions
                    SET lifecycle_state = 'PUBLISHED', published_at = ?, updated_at = ?
                    WHERE id = ?
                    """, flags, (statement, flag) -> {
                statement.setObject(1, java.sql.Timestamp.from(clock.instant()));
                statement.setObject(2, java.sql.Timestamp.from(clock.instant()));
                statement.setObject(3, flag.revisionId());
            });
            batch("""
                    INSERT INTO environment_flag_states (
                        tenant_id, project_id, environment_id, feature_flag_id,
                        revision_id, enabled, environment_version, updated_at
                    ) VALUES (?, ?, ?, ?, ?, true, 1, ?)
                    """, flags, (statement, flag) -> {
                statement.setObject(1, tenantId);
                statement.setObject(2, projectId);
                statement.setObject(3, environmentId);
                statement.setObject(4, flag.flagId());
                statement.setObject(5, flag.revisionId());
                statement.setObject(6, java.sql.Timestamp.from(clock.instant()));
            });
            jdbc.update("UPDATE environments SET current_snapshot_version = 1 WHERE id = ?", environmentId);
        });
    }

    private Publication publish(long expectedVersion) {
        UUID correlationId = UUID.randomUUID();
        var result = inTransaction(status -> service.publish(
                "acme", "checkout", "production", "alice",
                new Publish("flag-0", 1, true, expectedVersion, correlationId)));
        assertTrue(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM outbox_events WHERE snapshot_id = ?)",
                Boolean.class,
                result.snapshotId()));
        return new Publication(result.snapshotId(), result.snapshotVersion());
    }

    private <T> void batch(String sql, List<T> values, SqlBinder<T> binder) {
        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                binder.bind(statement, values.get(index));
            }

            @Override
            public int getBatchSize() {
                return values.size();
            }
        });
    }

    private UUID namedUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Number> percentiles(long[] values) {
        long[] sorted = Arrays.stream(values).sorted().toArray();
        return Map.of(
                "p50", micros(sorted, 0.50),
                "p95", micros(sorted, 0.95),
                "p99", micros(sorted, 0.99),
                "max", TimeUnit.NANOSECONDS.toMicros(sorted[sorted.length - 1]));
    }

    private Map<String, Long> percentilesRaw(long[] values) {
        long[] sorted = Arrays.stream(values).sorted().toArray();
        return Map.of(
                "p50", raw(sorted, 0.50),
                "p95", raw(sorted, 0.95),
                "p99", raw(sorted, 0.99),
                "max", sorted[sorted.length - 1]);
    }

    private long raw(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private long micros(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return TimeUnit.NANOSECONDS.toMicros(sorted[index]);
    }

    private static final class TimingSnapshotCompiler extends SnapshotCompiler {
        private final List<Long> samples = new ArrayList<>();

        private TimingSnapshotCompiler(ObjectMapper objectMapper) {
            super(objectMapper);
        }

        @Override
        public CompiledSnapshot compile(
                UUID snapshotId,
                long snapshotVersion,
                String tenantKey,
                String projectKey,
                String environmentKey,
                Instant generatedAt,
                List<io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublicationFlag>
                        flags) {
            long started = System.nanoTime();
            try {
                return super.compile(
                        snapshotId, snapshotVersion, tenantKey, projectKey, environmentKey, generatedAt, flags);
            } finally {
                samples.add(System.nanoTime() - started);
            }
        }

        private void clear() {
            samples.clear();
        }

        private long lastNanos() {
            return samples.get(samples.size() - 1);
        }

        private long[] samples() {
            return samples.stream().mapToLong(Long::longValue).toArray();
        }
    }

    private static final class TimingSnapshotValidator extends SnapshotValidator {
        private final List<Long> samples = new ArrayList<>();

        @Override
        public void validate(JsonNode payload) {
            long started = System.nanoTime();
            try {
                super.validate(payload);
            } finally {
                samples.add(System.nanoTime() - started);
            }
        }

        private void clear() {
            samples.clear();
        }

        private long lastNanos() {
            return samples.get(samples.size() - 1);
        }

        private long[] samples() {
            return samples.stream().mapToLong(Long::longValue).toArray();
        }
    }

    @FunctionalInterface
    private interface SqlBinder<T> {
        void bind(java.sql.PreparedStatement statement, T value) throws java.sql.SQLException;
    }

    private record SeedFlag(UUID flagId, UUID revisionId, String key) {
    }

    private record SeedVariant(UUID revisionId, String key, boolean value) {
    }

    private record Publication(UUID snapshotId, long version) {
    }
}
