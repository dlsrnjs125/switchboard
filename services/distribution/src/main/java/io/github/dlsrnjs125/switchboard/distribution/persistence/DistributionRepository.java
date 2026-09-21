package io.github.dlsrnjs125.switchboard.distribution.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.CredentialPrincipal;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

@Repository
public class DistributionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DistributionRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public Optional<CredentialPrincipal> authenticate(UUID credentialId, String secret) {
        return jdbc.query("""
                SELECT sc.id AS credential_id, sc.secret_hash, sc.status, sc.expires_at,
                       ca.id AS client_application_id, ca.client_application_key, ca.lifecycle_status,
                       t.id AS tenant_id, t.tenant_key,
                       p.id AS project_id, p.project_key,
                       e.id AS environment_id, e.environment_key
                FROM service_credentials sc
                JOIN client_applications ca
                  ON ca.tenant_id = sc.tenant_id AND ca.project_id = sc.project_id
                 AND ca.id = sc.client_application_id
                JOIN tenants t ON t.id = sc.tenant_id
                JOIN projects p ON p.tenant_id = sc.tenant_id AND p.id = sc.project_id
                JOIN environments e
                  ON e.tenant_id = ca.tenant_id AND e.project_id = ca.project_id
                 AND e.id = ca.environment_id
                WHERE sc.id = :credentialId
                """, Map.of("credentialId", credentialId), (rs, rowNum) -> new CredentialRow(
                        rs.getObject("credential_id", UUID.class),
                        rs.getString("secret_hash"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("client_application_id", UUID.class),
                        rs.getString("client_application_key"),
                        rs.getString("lifecycle_status"),
                        new EnvironmentScope(
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("project_id", UUID.class),
                                rs.getObject("environment_id", UUID.class),
                                rs.getString("tenant_key"),
                                rs.getString("project_key"),
                                rs.getString("environment_key"))))
                .stream()
                .filter(row -> row.active(clock.instant()))
                .filter(row -> passwordEncoder.matches(secret, row.secretHash()))
                .map(CredentialRow::principal)
                .findFirst();
    }

    public boolean isCredentialActive(UUID credentialId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM service_credentials sc
                JOIN client_applications ca
                  ON ca.tenant_id = sc.tenant_id AND ca.project_id = sc.project_id
                 AND ca.id = sc.client_application_id
                WHERE sc.id = :credentialId
                  AND sc.status = 'ACTIVE'
                  AND (sc.expires_at IS NULL OR sc.expires_at > :now)
                  AND ca.lifecycle_status = 'ACTIVE'
                """, Map.of("credentialId", credentialId, "now", Timestamp.from(clock.instant())), Integer.class);
        return count != null && count == 1;
    }

    public Optional<SnapshotArtifact> loadCurrentSnapshot(EnvironmentScope scope) {
        return loadSnapshot("""
                SELECT s.id, s.snapshot_version, s.schema_version, s.checksum,
                       s.payload::text, s.generated_at,
                       t.id AS tenant_id, t.tenant_key,
                       p.id AS project_id, p.project_key,
                       e.id AS environment_id, e.environment_key
                FROM environments e
                JOIN tenants t ON t.id = e.tenant_id
                JOIN projects p ON p.tenant_id = e.tenant_id AND p.id = e.project_id
                JOIN configuration_snapshots s
                  ON s.tenant_id = e.tenant_id AND s.project_id = e.project_id
                 AND s.environment_id = e.id AND s.snapshot_version = e.current_snapshot_version
                WHERE e.tenant_id = :tenantId AND e.project_id = :projectId AND e.id = :environmentId
                """, Map.of(
                "tenantId", scope.tenantId(),
                "projectId", scope.projectId(),
                "environmentId", scope.environmentId()));
    }

    public Optional<SnapshotArtifact> loadCurrentSnapshot(
            String tenantKey, String projectKey, String environmentKey) {
        return loadSnapshot("""
                SELECT s.id, s.snapshot_version, s.schema_version, s.checksum,
                       s.payload::text, s.generated_at,
                       t.id AS tenant_id, t.tenant_key,
                       p.id AS project_id, p.project_key,
                       e.id AS environment_id, e.environment_key
                FROM tenants t
                JOIN projects p ON p.tenant_id = t.id
                JOIN environments e ON e.tenant_id = t.id AND e.project_id = p.id
                JOIN configuration_snapshots s
                  ON s.tenant_id = e.tenant_id AND s.project_id = e.project_id
                 AND s.environment_id = e.id AND s.snapshot_version = e.current_snapshot_version
                WHERE t.tenant_key = :tenantKey AND p.project_key = :projectKey
                  AND e.environment_key = :environmentKey
                """, Map.of(
                "tenantKey", tenantKey,
                "projectKey", projectKey,
                "environmentKey", environmentKey));
    }

    public boolean isAvailable() {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT true", Map.of(), Boolean.class));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Optional<SnapshotArtifact> loadSnapshot(String sql, Map<String, ?> parameters) {
        return jdbc.query(sql, parameters, (rs, rowNum) -> {
            JsonNode payload = parse(rs.getString("payload"));
            String canonical = canonicalize(payload);
            EnvironmentScope scope = new EnvironmentScope(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getObject("environment_id", UUID.class),
                    rs.getString("tenant_key"),
                    rs.getString("project_key"),
                    rs.getString("environment_key"));
            return new SnapshotArtifact(
                    rs.getObject("id", UUID.class),
                    scope,
                    rs.getLong("snapshot_version"),
                    rs.getInt("schema_version"),
                    rs.getString("checksum"),
                    canonical.getBytes(StandardCharsets.UTF_8),
                    payload,
                    rs.getTimestamp("generated_at").toInstant());
        }).stream().findFirst();
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("stored snapshot is not valid JSON", exception);
        }
    }

    private String canonicalize(JsonNode payload) {
        try {
            return new JsonCanonicalizer(objectMapper.writeValueAsString(payload)).getEncodedString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("stored snapshot cannot be canonicalized", exception);
        }
    }

    private record CredentialRow(
            UUID credentialId,
            String secretHash,
            String status,
            Instant expiresAt,
            UUID clientApplicationId,
            String clientApplicationKey,
            String clientLifecycleStatus,
            EnvironmentScope scope) {
        private boolean active(Instant now) {
            return "ACTIVE".equals(status)
                    && "ACTIVE".equals(clientLifecycleStatus)
                    && (expiresAt == null || expiresAt.isAfter(now));
        }

        private CredentialPrincipal principal() {
            return new CredentialPrincipal(credentialId, clientApplicationId, clientApplicationKey, scope);
        }
    }
}
