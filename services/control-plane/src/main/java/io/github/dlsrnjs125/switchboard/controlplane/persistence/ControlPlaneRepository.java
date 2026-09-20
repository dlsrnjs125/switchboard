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
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Tenant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantRole;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantScope;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

            for (Allocation allocation : rule.allocations()) {
                jdbc.update("""
                        INSERT INTO rollout_allocations (rule_id, variant_key, revision_id, basis_points)
                        VALUES (:ruleId, :variantKey, :revisionId, :basisPoints)
                        """, Map.of(
                        "ruleId", ruleId,
                        "variantKey", allocation.variantKey(),
                        "revisionId", revisionId,
                        "basisPoints", allocation.basisPoints()));
            }
        }

        return new FlagRevision(revisionId, revisionNumber, RevisionLifecycleState.DRAFT);
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

    @FunctionalInterface
    public interface IdSource {
        UUID next();
    }
}
