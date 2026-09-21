package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Allocation;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rollback;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler.CompiledSnapshot;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotValidator;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.PublishResult;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.publication.OutboxRelay;
import io.github.dlsrnjs125.switchboard.controlplane.infrastructure.UuidV7Generator;
import io.github.dlsrnjs125.switchboard.controlplane.observability.ControlPlaneTelemetry;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublicationFlag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class PublicationIntegrationTest extends PostgresIntegrationSupport {
    @Test
    void publicationAtomicallyCreatesFullCanonicalSnapshotAuditAndOutbox() throws Exception {
        createBaseline();
        var first = createBooleanRevision("feature-a", false);
        var second = createBooleanRevision("feature-b", true);

        PublishResult firstResult = publish("feature-a", first.revisionNumber(), true, 0);
        PublishResult secondResult = publish("feature-b", second.revisionNumber(), false, 1);

        assertEquals(1, firstResult.snapshotVersion());
        assertEquals(2, secondResult.snapshotVersion());
        assertNotEquals(firstResult.snapshotId(), secondResult.snapshotId());
        JsonNode payload = new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT payload::text FROM configuration_snapshots WHERE id = ?",
                String.class,
                secondResult.snapshotId()));
        assertEquals(2, payload.path("flags").size());
        assertEquals("feature-a", payload.at("/flags/0/flagKey").asString());
        assertEquals("feature-b", payload.at("/flags/1/flagKey").asString());
        assertEquals(secondResult.checksum(), payload.path("checksum").asString());
        assertEquals(secondResult.checksum(), canonicalChecksum(payload));
        assertSnapshotContract(payload.toString());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT current_snapshot_version FROM environments WHERE environment_key = 'prod'", Long.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM snapshot_entries", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT lifecycle_state FROM flag_revisions WHERE id = ?", String.class, first.id()));
        assertEquals(secondResult.snapshotId(), inTransaction(status -> service.currentSnapshot(
                "acme", "checkout", "prod", "alice")).snapshotId());
    }

    @Test
    void staleExpectedVersionHasNoSideEffects() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        publish("feature-a", revision.revisionNumber(), true, 0);

        DomainException conflict = assertThrows(
                DomainException.class,
                () -> publish("feature-a", revision.revisionNumber(), false, 0));

        assertEquals("ENVIRONMENT_VERSION_CONFLICT", conflict.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT enabled FROM environment_flag_states", Boolean.class));
    }

    @Test
    void concurrentPublishWithSameExpectedVersionAllowsExactlyOneWinner() throws Exception {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> attempt = () -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                new TransactionTemplate(transaction.getTransactionManager()).execute(status -> service.publish(
                        "acme", "checkout", "prod", "alice",
                        new Publish("feature-a", revision.revisionNumber(), true, 0, UUID.randomUUID())));
                return "SUCCESS";
            } catch (DomainException exception) {
                return exception.code();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(attempt);
            Future<String> second = executor.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(
                    List.of("ENVIRONMENT_VERSION_CONFLICT", "SUCCESS"),
                    java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void rollbackSelectsHistoricalPublishedRevisionAndCreatesHigherVersion() {
        createBaseline();
        var revisionOne = createBooleanRevision("feature-a", false);
        publish("feature-a", revisionOne.revisionNumber(), true, 0);
        var revisionTwo = createBooleanRevision("feature-a", true);
        publish("feature-a", revisionTwo.revisionNumber(), true, 1);

        PublishResult rollback = inTransaction(status -> service.rollback(
                "acme", "checkout", "prod", "alice",
                new Rollback("feature-a", revisionOne.revisionNumber(), false, 2, UUID.randomUUID())));

        assertEquals(3, rollback.snapshotVersion());
        assertEquals(revisionOne.id(), jdbc.queryForObject("""
                SELECT revision_id FROM snapshot_entries WHERE snapshot_id = ?
                """, UUID.class, rollback.snapshotId()));
        assertEquals("FLAG_ROLLED_BACK", jdbc.queryForObject("""
                SELECT action FROM audit_events ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
    }

    @Test
    void transactionFailureLeavesNoPublicationResidue() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        var meters = new SimpleMeterRegistry();
        var instrumentedService = new ControlPlaneService(
                repository,
                new UuidV7Generator(),
                clock,
                new SnapshotCompiler(new ObjectMapper()),
                new SnapshotValidator(),
                new ControlPlaneTelemetry(meters, ObservationRegistry.NOOP));

        assertThrows(IllegalStateException.class, () -> inTransaction(status -> {
            instrumentedService.publish(
                    "acme", "checkout", "prod", "alice",
                    new Publish("feature-a", revision.revisionNumber(), true, 0, UUID.randomUUID()));
            throw new IllegalStateException("fail before commit");
        }));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM environment_flag_states", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM snapshot_entries", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT lifecycle_state FROM flag_revisions WHERE id = ?", String.class, revision.id()));
        assertNull(meters.find("switchboard.control.publish.total")
                .tags("operation", "publish", "outcome", "success").counter());
        assertEquals(1.0, meters.get("switchboard.control.publish.total")
                .tags("operation", "publish", "outcome", "failure", "reason", "transaction_rollback")
                .counter().count());
        assertEquals(1.0, meters.get("switchboard.control.transaction.total")
                .tags("operation", "publish", "outcome", "rolled_back")
                .counter().count());
    }

    @Test
    void postgresNetworkCutBeforePublishLeavesNoResidueAndRecoveryDoesNotReuseVersion() throws Exception {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);

        POSTGRES_PROXY.setConnectionCut(true);
        try {
            assertThrows(RuntimeException.class, () -> publish(
                    "feature-a", revision.revisionNumber(), true, 0));
        } finally {
            POSTGRES_PROXY.setConnectionCut(false);
        }

        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_snapshot_version FROM environments WHERE environment_key = 'prod'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM environment_flag_states", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM snapshot_entries", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));

        PublishResult recovered = publish("feature-a", revision.revisionNumber(), true, 0);
        assertEquals(1, recovered.snapshotVersion());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void ambiguousResponseAfterServerCommitIsReconciledWithoutBlindRetryOrVersionReuse() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        UUID correlationId = UUID.randomUUID();

        IllegalStateException ambiguous = assertThrows(IllegalStateException.class, () ->
                inTransaction(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            throw new IllegalStateException("injected response loss after server commit");
                        }
                    });
                    return service.publish(
                            "acme", "checkout", "prod", "alice",
                            new Publish(
                                    "feature-a", revision.revisionNumber(), true, 0, correlationId));
                }));

        assertEquals("injected response loss after server commit", ambiguous.getMessage());
        var committed = inTransaction(status -> service.currentSnapshot(
                "acme", "checkout", "prod", "alice"));
        assertEquals(1, committed.snapshotVersion());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT current_snapshot_version FROM environments WHERE environment_key = 'prod'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM environment_flag_states", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM snapshot_entries", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE correlation_id = ?", Integer.class, correlationId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        assertEquals(committed.snapshotId(), jdbc.queryForObject(
                "SELECT snapshot_id FROM outbox_events", UUID.class));

        DomainException blindRetry = assertThrows(DomainException.class, () -> inTransaction(status -> service.publish(
                "acme", "checkout", "prod", "alice",
                new Publish("feature-a", revision.revisionNumber(), true, 0, correlationId))));
        assertEquals("ENVIRONMENT_VERSION_CONFLICT", blindRetry.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));

        PublishResult next = publish("feature-a", revision.revisionNumber(), false, 1);
        assertEquals(2, next.snapshotVersion());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
    }

    @Test
    void invalidCompiledSnapshotFailsRuntimeValidationAndRollsBackPublication() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        ObjectMapper objectMapper = new ObjectMapper();
        SnapshotCompiler invalidCompiler = new SnapshotCompiler(objectMapper) {
            @Override
            public CompiledSnapshot compile(
                    UUID snapshotId,
                    long snapshotVersion,
                    String tenantKey,
                    String projectKey,
                    String environmentKey,
                    java.time.Instant generatedAt,
                    List<PublicationFlag> flags) {
                CompiledSnapshot compiled = super.compile(
                        snapshotId, snapshotVersion, tenantKey, projectKey, environmentKey, generatedAt, flags);
                ((ObjectNode) compiled.payload()).remove("schemaVersion");
                ((ObjectNode) compiled.payload().at("/flags/0")).put("defaultVariantKey", "missing");
                return compiled;
            }
        };
        ControlPlaneService invalidService = new ControlPlaneService(
                repository, new UuidV7Generator(), clock, invalidCompiler, new SnapshotValidator());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                inTransaction(status -> invalidService.publish(
                        "acme", "checkout", "prod", "alice",
                        new Publish("feature-a", revision.revisionNumber(), true, 0, UUID.randomUUID()))));

        assertTrue(failure.getMessage().startsWith("snapshot contract validation failed:"));
        assertTrue(failure.getMessage().contains("defaultVariantKey does not reference a variant"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_snapshot_version FROM environments WHERE environment_key = 'prod'", Long.class));
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT lifecycle_state FROM flag_revisions WHERE id = ?", String.class, revision.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM environment_flag_states", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void historicalSnapshotAndEntriesAreImmutableAndVersionCannotBeReused() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        PublishResult result = publish("feature-a", revision.revisionNumber(), true, 0);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE configuration_snapshots SET checksum = repeat('a', 64) WHERE id = ?",
                result.snapshotId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE snapshot_entries SET enabled = false WHERE snapshot_id = ?",
                result.snapshotId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO configuration_snapshots (
                    id, tenant_id, project_id, environment_id, snapshot_version,
                    schema_version, payload, checksum, generated_at
                )
                SELECT gen_random_uuid(), tenant_id, project_id, environment_id, snapshot_version,
                       schema_version, payload, repeat('b', 64), generated_at
                FROM configuration_snapshots WHERE id = ?
                """, result.snapshotId()));
    }

    @Test
    void snapshotPreservesAuthoredRolloutAllocationOrder() {
        createBaseline();
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("feature-a", ValueType.BOOLEAN)));
        CreateRevision command = new CreateRevision(
                List.of(
                        new Variant("off", JsonNodeFactory.instance.booleanNode(false)),
                        new Variant("on", JsonNodeFactory.instance.booleanNode(true))),
                "off",
                "seed",
                List.of(new Rule(
                        0,
                        RuleResultType.ROLLOUT,
                        null,
                        List.of(new Condition(0, "country", "EXISTS", null)),
                        List.of(new Allocation("on", 2_500), new Allocation("off", 7_500)))));
        var revision = inTransaction(status -> service.createDraftRevision(
                "acme", "checkout", "feature-a", "alice", command));

        PublishResult result = publish("feature-a", revision.revisionNumber(), true, 0);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM configuration_snapshots WHERE id = ?",
                String.class,
                result.snapshotId());
        JsonNode snapshot = readJson(payload);

        assertEquals("on", snapshot.at("/flags/0/rules/0/result/allocations/0/variantKey").asString());
        assertEquals("off", snapshot.at("/flags/0/rules/0/result/allocations/1/variantKey").asString());
        assertEquals(List.of(0, 1), jdbc.queryForList(
                "SELECT allocation_order FROM rollout_allocations ORDER BY allocation_order", Integer.class));
    }

    @Test
    void crossTenantPublishIsHiddenAndCreatesNoSideEffects() {
        createBaseline();
        createBooleanRevision("feature-a", false);
        inTransaction(status -> service.createTenant("other", "Other", "bob"));
        inTransaction(status -> service.createProject("other", "bob", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "other", "checkout", "bob", new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));

        DomainException hidden = assertThrows(DomainException.class, () -> inTransaction(status -> service.publish(
                "other", "checkout", "prod", "alice",
                new Publish("feature-a", 1, true, 0, UUID.randomUUID()))));

        assertEquals("RESOURCE_NOT_FOUND", hidden.code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM configuration_snapshots", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void outboxRetriesWithStableEventIdAndMarksPublishedOnlyAfterAck() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        publish("feature-a", revision.revisionNumber(), true, 0);
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events", UUID.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<UUID> observedId = new AtomicReference<>();
        OutboxRelay relay = new OutboxRelay(repository, (id, payload) -> {
            assertTrue(!TransactionSynchronizationManager.isActualTransactionActive(),
                    "publisher I/O must run outside a database transaction");
            observedId.set(id);
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("Kafka unavailable");
            }
        }, clock, transaction.getTransactionManager());

        relay.relay();
        assertEquals(eventId, observedId.get());
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM outbox_events", Object.class));
        assertNull(jdbc.queryForObject("SELECT claimed_at FROM outbox_events", Object.class));

        jdbc.update("UPDATE outbox_events SET next_attempt_at = '2026-09-19T00:00:00Z' WHERE id = ?", eventId);
        relay.relay();
        assertEquals(eventId, observedId.get());
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM outbox_events", Object.class));
        assertNull(jdbc.queryForObject("SELECT claimed_at FROM outbox_events", Object.class));
    }

    @Test
    void expiredOutboxLeaseIsReclaimedAndOnlyCurrentClaimCanCompleteDelivery() {
        createBaseline();
        var revision = createBooleanRevision("feature-a", false);
        publish("feature-a", revision.revisionNumber(), true, 0);
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events", UUID.class);
        Instant initialClaimAt = clock.instant();
        UUID crashedRelayClaim = UUID.randomUUID();
        UUID recoveryRelayClaim = UUID.randomUUID();

        assertEquals(eventId, repository.claimNextOutbox(
                crashedRelayClaim, initialClaimAt, initialClaimAt.minusSeconds(60)).orElseThrow().id());
        assertTrue(repository.claimNextOutbox(
                recoveryRelayClaim, initialClaimAt.plusSeconds(30), initialClaimAt.minusSeconds(30)).isEmpty());
        assertEquals(eventId, repository.claimNextOutbox(
                recoveryRelayClaim, initialClaimAt.plusSeconds(61), initialClaimAt.plusSeconds(1))
                .orElseThrow().id());

        repository.markOutboxPublished(eventId, crashedRelayClaim, initialClaimAt.plusSeconds(62));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        repository.markOutboxPublished(eventId, recoveryRelayClaim, initialClaimAt.plusSeconds(63));

        assertNotNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM outbox_events", Object.class));
    }

    private void createBaseline() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject("acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "acme", "checkout", "alice", new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));
    }

    private io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision createBooleanRevision(
            String flagKey, boolean defaultValue) {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM feature_flags WHERE flag_key = ?", Integer.class, flagKey);
        if (existing == 0) {
            inTransaction(status -> service.createFlag(
                    "acme", "checkout", "alice", new CreateFlag(flagKey, ValueType.BOOLEAN)));
        }
        return inTransaction(status -> service.createDraftRevision(
                "acme",
                "checkout",
                flagKey,
                "alice",
                new CreateRevision(
                        List.of(
                                new Variant("off", JsonNodeFactory.instance.booleanNode(defaultValue)),
                                new Variant("on", JsonNodeFactory.instance.booleanNode(!defaultValue))),
                        "off",
                        flagKey + "-seed",
                        List.of())));
    }

    private PublishResult publish(String flagKey, long revisionNumber, boolean enabled, long expectedVersion) {
        return inTransaction(status -> service.publish(
                "acme", "checkout", "prod", "alice",
                new Publish(flagKey, revisionNumber, enabled, expectedVersion, UUID.randomUUID())));
    }

    private String canonicalChecksum(JsonNode payload) throws Exception {
        ObjectNode unsigned = (ObjectNode) payload.deepCopy();
        unsigned.remove("checksum");
        String canonical = new JsonCanonicalizer(unsigned.toString()).getEncodedString();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode readJson(String payload) {
        try {
            return new ObjectMapper().readTree(payload);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private void assertSnapshotContract(String payload) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path schemaPath = Path.of(
                System.getProperty("switchboard.repositoryRoot"),
                "contracts",
                "snapshot-schema",
                "configuration-snapshot-v1.schema.json");
        com.networknt.schema.JsonSchema schema = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(schemaPath.toFile()));
        assertEquals(java.util.Set.of(), schema.validate(json.readTree(payload)));
    }
}
