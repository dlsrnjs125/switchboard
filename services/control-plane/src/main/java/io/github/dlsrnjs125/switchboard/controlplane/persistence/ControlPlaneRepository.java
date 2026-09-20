package io.github.dlsrnjs125.switchboard.controlplane.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Allocation;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Environment;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FeatureFlag;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagLifecycleStatus;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Project;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RevisionLifecycleState;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.SnapshotSummary;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Tenant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantRole;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantScope;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ControlPlaneRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ControlPlaneRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Tenant createTenant(UUID id, String tenantKey, String name, Instant now) {
        jdbc.update("""
                INSERT INTO tenants (id, tenant_key, name, created_at, updated_at)
                VALUES (:id, :tenantKey, :name, :now, :now)
                """, Map.of("id", id, "tenantKey", tenantKey, "name", name, "now", Timestamp.from(now)));
        return new Tenant(id, tenantKey, name, now);
    }

    public void addTenantMember(UUID tenantId, String principalId, TenantRole role, Instant now) {
        jdbc.update("""
                INSERT INTO tenant_members (tenant_id, principal_id, role, created_at, updated_at)
                VALUES (:tenantId, :principalId, :role, :now, :now)
                """, Map.of(
                "tenantId", tenantId,
                "principalId", principalId,
                "role", role.name(),
                "now", Timestamp.from(now)));
    }

    public Optional<TenantScope> findTenantScope(String tenantKey, String principalId) {
        return jdbc.query("""
                SELECT t.id, t.tenant_key, tm.role
                FROM tenants t
                JOIN tenant_members tm ON tm.tenant_id = t.id
                WHERE t.tenant_key = :tenantKey AND tm.principal_id = :principalId
                """, Map.of("tenantKey", tenantKey, "principalId", principalId),
                (rs, rowNum) -> new TenantScope(
                        rs.getObject("id", UUID.class),
                        rs.getString("tenant_key"),
                        TenantRole.valueOf(rs.getString("role"))))
                .stream()
                .findFirst();
    }

    public Project createProject(UUID id, TenantScope scope, String projectKey, String name, Instant now) {
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, project_key, name, created_at, updated_at)
                VALUES (:id, :tenantId, :projectKey, :name, :now, :now)
                """, Map.of(
                "id", id,
                "tenantId", scope.tenantId(),
                "projectKey", projectKey,
                "name", name,
                "now", Timestamp.from(now)));
        return new Project(id, scope.tenantKey(), projectKey, name);
    }

    public List<Project> listProjects(TenantScope scope) {
        return jdbc.query("""
                SELECT id, project_key, name
                FROM projects
                WHERE tenant_id = :tenantId AND archived_at IS NULL
                ORDER BY created_at, id
                """, Map.of("tenantId", scope.tenantId()),
                (rs, rowNum) -> new Project(
                        rs.getObject("id", UUID.class),
                        scope.tenantKey(),
                        rs.getString("project_key"),
                        rs.getString("name")));
    }

    public ScopedProject requireProject(TenantScope scope, String projectKey) {
        return jdbc.query("""
                SELECT id, project_key
                FROM projects
                WHERE tenant_id = :tenantId AND project_key = :projectKey AND archived_at IS NULL
                """, Map.of("tenantId", scope.tenantId(), "projectKey", projectKey),
                (rs, rowNum) -> new ScopedProject(rs.getObject("id", UUID.class), rs.getString("project_key")))
                .stream()
                .findFirst()
                .orElseThrow(DomainException::notFound);
    }

    public Environment createEnvironment(
            UUID id,
            TenantScope scope,
            ScopedProject project,
            String environmentKey,
            String name,
            EnvironmentType type,
            Instant now) {
        jdbc.update("""
                INSERT INTO environments (
                    id, tenant_id, project_id, environment_key, name, environment_type,
                    current_snapshot_version, created_at, updated_at
                ) VALUES (
                    :id, :tenantId, :projectId, :environmentKey, :name, :type, 0, :now, :now
                )
                """, Map.of(
                "id", id,
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "environmentKey", environmentKey,
                "name", name,
                "type", type.name(),
                "now", Timestamp.from(now)));
        return new Environment(id, environmentKey, type, 0L);
    }

    public FeatureFlag createFlag(
            UUID id,
            TenantScope scope,
            ScopedProject project,
            String flagKey,
            ValueType valueType,
            Instant now) {
        jdbc.update("""
                INSERT INTO feature_flags (
                    id, tenant_id, project_id, flag_key, value_type, lifecycle_status, created_at, updated_at
                ) VALUES (:id, :tenantId, :projectId, :flagKey, :valueType, 'ACTIVE', :now, :now)
                """, Map.of(
                "id", id,
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "flagKey", flagKey,
                "valueType", valueType.name(),
                "now", Timestamp.from(now)));
        return new FeatureFlag(id, flagKey, valueType, FlagLifecycleStatus.ACTIVE);
    }

    public List<FeatureFlag> listFlags(TenantScope scope, ScopedProject project) {
        return jdbc.query("""
                SELECT id, flag_key, value_type, lifecycle_status
                FROM feature_flags
                WHERE tenant_id = :tenantId AND project_id = :projectId
                ORDER BY created_at, id
                """, Map.of("tenantId", scope.tenantId(), "projectId", project.id()),
                (rs, rowNum) -> mapFlag(rs));
    }

    public ScopedFlag lockFlag(TenantScope scope, ScopedProject project, String flagKey) {
        return jdbc.query("""
                SELECT id, value_type, lifecycle_status
                FROM feature_flags
                WHERE tenant_id = :tenantId AND project_id = :projectId AND flag_key = :flagKey
                FOR UPDATE
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "flagKey", flagKey),
                (rs, rowNum) -> new ScopedFlag(
                        rs.getObject("id", UUID.class),
                        ValueType.valueOf(rs.getString("value_type")),
                        FlagLifecycleStatus.valueOf(rs.getString("lifecycle_status"))))
                .stream()
                .findFirst()
                .orElseThrow(DomainException::notFound);
    }

    public long nextRevisionNumber(UUID flagId) {
        Long next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                FROM flag_revisions
                WHERE feature_flag_id = :flagId
                """, Map.of("flagId", flagId), Long.class);
        return next == null ? 1L : next;
    }

    public FlagRevision insertDraft(
            UUID revisionId,
            TenantScope scope,
            ScopedProject project,
            ScopedFlag flag,
            long revisionNumber,
            String defaultVariantKey,
            String rolloutSeed,
            List<Variant> variants,
            List<Rule> rules,
            Instant now,
            IdSource ids) {
        jdbc.update("""
                INSERT INTO flag_revisions (
                    id, tenant_id, project_id, feature_flag_id, revision_number, value_type,
                    lifecycle_state, default_variant_key, rollout_seed, created_at, updated_at
                ) VALUES (
                    :id, :tenantId, :projectId, :flagId, :revisionNumber, :valueType,
                    'DRAFT', :defaultVariantKey, :rolloutSeed, :now, :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", revisionId)
                .addValue("tenantId", scope.tenantId())
                .addValue("projectId", project.id())
                .addValue("flagId", flag.id())
                .addValue("revisionNumber", revisionNumber)
                .addValue("valueType", flag.valueType().name())
                .addValue("defaultVariantKey", defaultVariantKey)
                .addValue("rolloutSeed", rolloutSeed)
                .addValue("now", Timestamp.from(now)));

        for (Variant variant : variants) {
            jdbc.update("""
                    INSERT INTO flag_variants (revision_id, variant_key, value_type, value)
                    VALUES (:revisionId, :variantKey, :valueType, CAST(:value AS jsonb))
                    """, Map.of(
                    "revisionId", revisionId,
                    "variantKey", variant.key(),
                    "valueType", flag.valueType().name(),
                    "value", json(variant.value())));
        }

        for (Rule rule : rules) {
            UUID ruleId = ids.next();
            MapSqlParameterSource ruleParameters = new MapSqlParameterSource()
                    .addValue("id", ruleId)
                    .addValue("revisionId", revisionId)
                    .addValue("priority", rule.priority())
                    .addValue("resultType", rule.resultType().name())
                    .addValue("resultVariantKey", rule.resultVariantKey());
            jdbc.update("""
                    INSERT INTO targeting_rules (id, revision_id, priority, result_type, result_variant_key)
                    VALUES (:id, :revisionId, :priority, :resultType, :resultVariantKey)
                    """, ruleParameters);

            for (Condition condition : rule.conditions()) {
                MapSqlParameterSource conditionParameters = new MapSqlParameterSource()
                        .addValue("id", ids.next())
                        .addValue("ruleId", ruleId)
                        .addValue("conditionOrder", condition.order())
                        .addValue("attribute", condition.attribute())
                        .addValue("operator", condition.operator())
                        .addValue("operand", condition.operand() == null || condition.operand().isNull()
                                ? null
                                : json(condition.operand()));
                jdbc.update("""
                        INSERT INTO rule_conditions (id, rule_id, condition_order, attribute, operator, operand)
                        VALUES (:id, :ruleId, :conditionOrder, :attribute, :operator, CAST(:operand AS jsonb))
                        """, conditionParameters);
            }

            for (int allocationOrder = 0; allocationOrder < rule.allocations().size(); allocationOrder++) {
                Allocation allocation = rule.allocations().get(allocationOrder);
                jdbc.update("""
                        INSERT INTO rollout_allocations (
                            rule_id, variant_key, revision_id, basis_points, allocation_order
                        ) VALUES (
                            :ruleId, :variantKey, :revisionId, :basisPoints, :allocationOrder
                        )
                        """, Map.of(
                        "ruleId", ruleId,
                        "variantKey", allocation.variantKey(),
                        "revisionId", revisionId,
                        "basisPoints", allocation.basisPoints(),
                        "allocationOrder", allocationOrder));
            }
        }

        return new FlagRevision(revisionId, revisionNumber, RevisionLifecycleState.DRAFT);
    }

    public ScopedEnvironment lockEnvironment(
            TenantScope scope, ScopedProject project, String environmentKey) {
        return jdbc.query("""
                SELECT id, environment_key, current_snapshot_version
                FROM environments
                WHERE tenant_id = :tenantId AND project_id = :projectId
                  AND environment_key = :environmentKey AND archived_at IS NULL
                FOR UPDATE
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "environmentKey", environmentKey),
                (rs, rowNum) -> new ScopedEnvironment(
                        rs.getObject("id", UUID.class),
                        rs.getString("environment_key"),
                        rs.getLong("current_snapshot_version")))
                .stream()
                .findFirst()
                .orElseThrow(DomainException::notFound);
    }

    public ScopedRevision requireRevision(
            TenantScope scope, ScopedProject project, String flagKey, long revisionNumber) {
        return jdbc.query("""
                SELECT f.id AS flag_id, f.flag_key, f.value_type, f.lifecycle_status,
                       r.id AS revision_id, r.revision_number, r.lifecycle_state
                FROM feature_flags f
                JOIN flag_revisions r
                  ON r.tenant_id = f.tenant_id
                 AND r.project_id = f.project_id
                 AND r.feature_flag_id = f.id
                WHERE f.tenant_id = :tenantId AND f.project_id = :projectId
                  AND f.flag_key = :flagKey AND r.revision_number = :revisionNumber
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "flagKey", flagKey,
                "revisionNumber", revisionNumber),
                (rs, rowNum) -> new ScopedRevision(
                        rs.getObject("flag_id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getString("flag_key"),
                        rs.getLong("revision_number"),
                        ValueType.valueOf(rs.getString("value_type")),
                        FlagLifecycleStatus.valueOf(rs.getString("lifecycle_status")),
                        RevisionLifecycleState.valueOf(rs.getString("lifecycle_state"))))
                .stream()
                .findFirst()
                .orElseThrow(DomainException::notFound);
    }

    public void markRevisionPublished(ScopedRevision revision, Instant now) {
        if (revision.lifecycleState() == RevisionLifecycleState.PUBLISHED) {
            return;
        }
        int updated = jdbc.update("""
                UPDATE flag_revisions
                SET lifecycle_state = 'PUBLISHED', published_at = :now, updated_at = :now
                WHERE id = :revisionId AND lifecycle_state = 'DRAFT'
                """, Map.of("revisionId", revision.revisionId(), "now", Timestamp.from(now)));
        if (updated != 1) {
            throw DomainException.notFound();
        }
    }

    public void putEnvironmentFlagState(
            TenantScope scope,
            ScopedProject project,
            ScopedEnvironment environment,
            ScopedRevision revision,
            boolean enabled,
            long environmentVersion,
            Instant now) {
        jdbc.update("""
                INSERT INTO environment_flag_states (
                    tenant_id, project_id, environment_id, feature_flag_id, revision_id,
                    enabled, environment_version, updated_at
                ) VALUES (
                    :tenantId, :projectId, :environmentId, :flagId, :revisionId,
                    :enabled, :environmentVersion, :now
                )
                ON CONFLICT (environment_id, feature_flag_id) DO UPDATE
                SET revision_id = EXCLUDED.revision_id,
                    enabled = EXCLUDED.enabled,
                    environment_version = EXCLUDED.environment_version,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("projectId", project.id())
                .addValue("environmentId", environment.id())
                .addValue("flagId", revision.flagId())
                .addValue("revisionId", revision.revisionId())
                .addValue("enabled", enabled)
                .addValue("environmentVersion", environmentVersion)
                .addValue("now", Timestamp.from(now)));
    }

    public List<PublicationFlag> loadPublicationFlags(
            TenantScope scope, ScopedProject project, ScopedEnvironment environment) {
        List<PublicationFlagRow> rows = jdbc.query("""
                SELECT f.id AS flag_id, f.flag_key, f.value_type,
                       r.id AS revision_id, r.revision_number, r.default_variant_key, r.rollout_seed,
                       s.enabled
                FROM environment_flag_states s
                JOIN feature_flags f ON f.id = s.feature_flag_id
                JOIN flag_revisions r ON r.id = s.revision_id
                WHERE s.tenant_id = :tenantId AND s.project_id = :projectId
                  AND s.environment_id = :environmentId
                ORDER BY f.flag_key
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "environmentId", environment.id()),
                (rs, rowNum) -> new PublicationFlagRow(
                        rs.getObject("flag_id", UUID.class),
                        rs.getObject("revision_id", UUID.class),
                        rs.getString("flag_key"),
                        rs.getLong("revision_number"),
                        ValueType.valueOf(rs.getString("value_type")),
                        rs.getBoolean("enabled"),
                        rs.getString("default_variant_key"),
                        rs.getString("rollout_seed")));

        List<PublicationFlag> flags = new ArrayList<>(rows.size());
        for (PublicationFlagRow row : rows) {
            List<PublishedVariant> variants = jdbc.query("""
                    SELECT variant_key, value
                    FROM flag_variants
                    WHERE revision_id = :revisionId
                    ORDER BY variant_key
                    """, Map.of("revisionId", row.revisionId()),
                    (rs, rowNum) -> new PublishedVariant(
                            rs.getString("variant_key"), parseJson(rs.getString("value"))));
            List<PublishedRule> rules = loadRules(row.revisionId());
            flags.add(new PublicationFlag(
                    row.flagId(), row.revisionId(), row.flagKey(), row.revisionNumber(), row.valueType(),
                    row.enabled(), row.defaultVariantKey(), row.rolloutSeed(), variants, rules));
        }
        return List.copyOf(flags);
    }

    private List<PublishedRule> loadRules(UUID revisionId) {
        List<PublishedRuleRow> rows = jdbc.query("""
                SELECT id, priority, result_type, result_variant_key
                FROM targeting_rules
                WHERE revision_id = :revisionId
                ORDER BY priority
                """, Map.of("revisionId", revisionId),
                (rs, rowNum) -> new PublishedRuleRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("priority"),
                        RuleResultType.valueOf(rs.getString("result_type")),
                        rs.getString("result_variant_key")));
        List<PublishedRule> rules = new ArrayList<>(rows.size());
        for (PublishedRuleRow row : rows) {
            List<PublishedCondition> conditions = jdbc.query("""
                    SELECT attribute, operator, operand
                    FROM rule_conditions
                    WHERE rule_id = :ruleId
                    ORDER BY condition_order
                    """, Map.of("ruleId", row.id()),
                    (rs, rowNum) -> new PublishedCondition(
                            rs.getString("attribute"),
                            rs.getString("operator"),
                            rs.getString("operand") == null ? null : parseJson(rs.getString("operand"))));
            List<PublishedAllocation> allocations = jdbc.query("""
                    SELECT variant_key, basis_points
                    FROM rollout_allocations
                    WHERE rule_id = :ruleId
                    ORDER BY allocation_order
                    """, Map.of("ruleId", row.id()),
                    (rs, rowNum) -> new PublishedAllocation(
                            rs.getString("variant_key"), rs.getInt("basis_points")));
            rules.add(new PublishedRule(
                    row.priority(), row.resultType(), row.resultVariantKey(), conditions, allocations));
        }
        return List.copyOf(rules);
    }

    public void advanceEnvironmentVersion(
            TenantScope scope,
            ScopedProject project,
            ScopedEnvironment environment,
            long expectedVersion,
            long nextVersion,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE environments
                SET current_snapshot_version = :nextVersion, updated_at = :now
                WHERE tenant_id = :tenantId AND project_id = :projectId AND id = :environmentId
                  AND current_snapshot_version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", scope.tenantId())
                .addValue("projectId", project.id())
                .addValue("environmentId", environment.id())
                .addValue("expectedVersion", expectedVersion)
                .addValue("nextVersion", nextVersion)
                .addValue("now", Timestamp.from(now)));
        if (updated != 1) {
            throw DomainException.environmentVersionConflict();
        }
    }

    public void insertPublication(
            UUID snapshotId,
            UUID auditId,
            UUID outboxId,
            TenantScope scope,
            ScopedProject project,
            ScopedEnvironment environment,
            ScopedRevision changedRevision,
            long snapshotVersion,
            JsonNode payload,
            String checksum,
            boolean enabled,
            String actorId,
            UUID correlationId,
            String action,
            Instant now,
            List<PublicationFlag> flags) {
        jdbc.update("""
                INSERT INTO configuration_snapshots (
                    id, tenant_id, project_id, environment_id, snapshot_version,
                    schema_version, payload, checksum, generated_at
                ) VALUES (
                    :id, :tenantId, :projectId, :environmentId, :snapshotVersion,
                    1, CAST(:payload AS jsonb), :checksum, :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", snapshotId)
                .addValue("tenantId", scope.tenantId())
                .addValue("projectId", project.id())
                .addValue("environmentId", environment.id())
                .addValue("snapshotVersion", snapshotVersion)
                .addValue("payload", json(payload))
                .addValue("checksum", checksum)
                .addValue("now", Timestamp.from(now)));

        for (PublicationFlag flag : flags) {
            jdbc.update("""
                    INSERT INTO snapshot_entries (
                        snapshot_id, tenant_id, project_id, environment_id,
                        feature_flag_id, revision_id, enabled
                    ) VALUES (
                        :snapshotId, :tenantId, :projectId, :environmentId,
                        :flagId, :revisionId, :enabled
                    )
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", snapshotId)
                    .addValue("tenantId", scope.tenantId())
                    .addValue("projectId", project.id())
                    .addValue("environmentId", environment.id())
                    .addValue("flagId", flag.flagId())
                    .addValue("revisionId", flag.revisionId())
                    .addValue("enabled", flag.enabled()));
        }

        ObjectNodeFactory nodes = new ObjectNodeFactory(objectMapper);
        JsonNode auditDetails = nodes.auditDetails(
                changedRevision.flagKey(), changedRevision.revisionNumber(), enabled, snapshotVersion, checksum, action);
        jdbc.update("""
                INSERT INTO audit_events (
                    id, tenant_id, actor_type, actor_id, action, resource_type,
                    resource_id, correlation_id, details, created_at
                ) VALUES (
                    :id, :tenantId, 'USER', :actorId, :action, 'ENVIRONMENT',
                    :environmentId, :correlationId, CAST(:details AS jsonb), :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", auditId)
                .addValue("tenantId", scope.tenantId())
                .addValue("actorId", actorId)
                .addValue("action", action)
                .addValue("environmentId", environment.id())
                .addValue("correlationId", correlationId)
                .addValue("details", json(auditDetails))
                .addValue("now", Timestamp.from(now)));

        JsonNode eventPayload = nodes.eventPayload(
                outboxId, scope.tenantKey(), project.key(), environment.key(), snapshotId,
                snapshotVersion, checksum, now);
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, tenant_id, snapshot_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, created_at, next_attempt_at
                ) VALUES (
                    :id, :tenantId, :snapshotId, 'ENVIRONMENT', :environmentId,
                    'SNAPSHOT_PUBLISHED', 1, CAST(:payload AS jsonb), :now, :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", outboxId)
                .addValue("tenantId", scope.tenantId())
                .addValue("snapshotId", snapshotId)
                .addValue("environmentId", environment.id())
                .addValue("payload", json(eventPayload))
                .addValue("now", Timestamp.from(now)));
    }

    public Optional<SnapshotSummary> findCurrentSnapshot(
            TenantScope scope, ScopedProject project, String environmentKey) {
        return jdbc.query("""
                SELECT s.id, s.snapshot_version, s.schema_version, s.checksum
                FROM environments e
                JOIN configuration_snapshots s
                  ON s.tenant_id = e.tenant_id AND s.project_id = e.project_id
                 AND s.environment_id = e.id AND s.snapshot_version = e.current_snapshot_version
                WHERE e.tenant_id = :tenantId AND e.project_id = :projectId
                  AND e.environment_key = :environmentKey AND e.archived_at IS NULL
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", project.id(),
                "environmentKey", environmentKey),
                (rs, rowNum) -> new SnapshotSummary(
                        rs.getObject("id", UUID.class),
                        rs.getLong("snapshot_version"),
                        rs.getInt("schema_version"),
                        rs.getString("checksum")))
                .stream()
                .findFirst();
    }

    public List<OutboxEvent> lockPendingOutbox(int limit, Instant now) {
        return jdbc.query("""
                SELECT id, payload, attempt_count
                FROM outbox_events
                WHERE published_at IS NULL AND next_attempt_at <= :now
                ORDER BY next_attempt_at, created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Map.of("now", Timestamp.from(now), "limit", limit),
                (rs, rowNum) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        parseJson(rs.getString("payload")),
                        rs.getInt("attempt_count")));
    }

    public void markOutboxPublished(UUID eventId, Instant now) {
        jdbc.update("""
                UPDATE outbox_events
                SET published_at = :now, attempt_count = attempt_count + 1, last_error = NULL
                WHERE id = :id AND published_at IS NULL
                """, Map.of("id", eventId, "now", Timestamp.from(now)));
    }

    public void markOutboxFailed(UUID eventId, Instant nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE outbox_events
                SET attempt_count = attempt_count + 1, next_attempt_at = :nextAttemptAt, last_error = :error
                WHERE id = :id AND published_at IS NULL
                """, Map.of(
                "id", eventId,
                "nextAttemptAt", Timestamp.from(nextAttemptAt),
                "error", error == null ? "publisher failed" : error));
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored JSON cannot be parsed", exception);
        }
    }

    private FeatureFlag mapFlag(ResultSet rs) throws SQLException {
        return new FeatureFlag(
                rs.getObject("id", UUID.class),
                rs.getString("flag_key"),
                ValueType.valueOf(rs.getString("value_type")),
                FlagLifecycleStatus.valueOf(rs.getString("lifecycle_status")));
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON value cannot be serialized", exception);
        }
    }

    public record ScopedProject(UUID id, String key) {
    }

    public record ScopedFlag(UUID id, ValueType valueType, FlagLifecycleStatus status) {
    }

    public record ScopedEnvironment(UUID id, String key, long currentSnapshotVersion) {
    }

    public record ScopedRevision(
            UUID flagId,
            UUID revisionId,
            String flagKey,
            long revisionNumber,
            ValueType valueType,
            FlagLifecycleStatus flagStatus,
            RevisionLifecycleState lifecycleState) {
    }

    private record PublicationFlagRow(
            UUID flagId,
            UUID revisionId,
            String flagKey,
            long revisionNumber,
            ValueType valueType,
            boolean enabled,
            String defaultVariantKey,
            String rolloutSeed) {
    }

    private record PublishedRuleRow(
            UUID id, int priority, RuleResultType resultType, String resultVariantKey) {
    }

    public record PublicationFlag(
            UUID flagId,
            UUID revisionId,
            String flagKey,
            long revisionNumber,
            ValueType valueType,
            boolean enabled,
            String defaultVariantKey,
            String rolloutSeed,
            List<PublishedVariant> variants,
            List<PublishedRule> rules) {
    }

    public record PublishedVariant(String key, JsonNode value) {
    }

    public record PublishedRule(
            int priority,
            RuleResultType resultType,
            String resultVariantKey,
            List<PublishedCondition> conditions,
            List<PublishedAllocation> allocations) {
    }

    public record PublishedCondition(String attribute, String operator, JsonNode operand) {
    }

    public record PublishedAllocation(String variantKey, int basisPoints) {
    }

    public record OutboxEvent(UUID id, JsonNode payload, int attemptCount) {
    }

    private static final class ObjectNodeFactory {
        private final ObjectMapper mapper;

        private ObjectNodeFactory(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        private JsonNode auditDetails(
                String flagKey,
                long revisionNumber,
                boolean enabled,
                long snapshotVersion,
                String checksum,
                String sourceAction) {
            return mapper.createObjectNode()
                    .put("flagKey", flagKey)
                    .put("revisionNumber", revisionNumber)
                    .put("enabled", enabled)
                    .put("snapshotVersion", snapshotVersion)
                    .put("checksum", checksum)
                    .put("sourceAction", sourceAction);
        }

        private JsonNode eventPayload(
                UUID eventId,
                String tenantKey,
                String projectKey,
                String environmentKey,
                UUID snapshotId,
                long snapshotVersion,
                String checksum,
                Instant occurredAt) {
            return mapper.createObjectNode()
                    .put("eventId", eventId.toString())
                    .put("eventType", "SNAPSHOT_PUBLISHED")
                    .put("eventVersion", 1)
                    .put("tenantKey", tenantKey)
                    .put("projectKey", projectKey)
                    .put("environmentKey", environmentKey)
                    .put("snapshotId", snapshotId.toString())
                    .put("snapshotVersion", snapshotVersion)
                    .put("checksum", checksum)
                    .put("occurredAt", occurredAt.toString());
        }
    }

    @FunctionalInterface
    public interface IdSource {
        UUID next();
    }
}
