package io.github.dlsrnjs125.switchboard.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.EnvironmentScope;
import io.github.dlsrnjs125.switchboard.distribution.persistence.DistributionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.erdtman.jcs.JsonCanonicalizer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class DistributionPostgresSupport {
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");
    protected static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Path migrations = Path.of(
                System.getProperty("switchboard.repositoryRoot"),
                "services", "control-plane", "src", "main", "resources", "db", "migration");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations.toAbsolutePath())
                .load()
                .migrate();
        DATA_SOURCE = dataSource;
    }

    protected final UUID tenantId = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    protected final UUID projectId = UUID.fromString("018f1000-0000-7000-8000-000000000002");
    protected final UUID environmentId = UUID.fromString("018f1000-0000-7000-8000-000000000003");
    protected final UUID applicationId = UUID.fromString("018f1000-0000-7000-8000-000000000004");
    protected final UUID credentialId = UUID.fromString("018f1000-0000-7000-8000-000000000005");
    protected final String secret = "test-secret-material-with-enough-entropy";
    protected final Clock clock = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC);
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    protected JdbcTemplate jdbc;
    protected DistributionRepository repository;

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(DATA_SOURCE);
        jdbc.execute("TRUNCATE TABLE tenants CASCADE");
        repository = new DistributionRepository(
                new NamedParameterJdbcTemplate(DATA_SOURCE), objectMapper, passwordEncoder, clock);
        insertScope();
    }

    protected EnvironmentScope scope() {
        return new EnvironmentScope(tenantId, projectId, environmentId, "acme", "checkout", "production");
    }

    protected SnapshotFixture insertSnapshot(long version) {
        UUID snapshotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("snapshotId", snapshotId.toString());
        payload.put("snapshotVersion", version);
        payload.put("tenantKey", "acme");
        payload.put("projectKey", "checkout");
        payload.put("environmentKey", "production");
        payload.put("generatedAt", clock.instant().toString());
        ObjectNode flag = payload.putArray("flags").addObject();
        flag.put("flagKey", "checkout-v2");
        flag.put("revisionNumber", version);
        flag.put("valueType", "BOOLEAN");
        flag.put("enabled", true);
        flag.put("defaultVariantKey", "on");
        flag.put("rolloutSeed", "checkout-seed");
        var variants = flag.putArray("variants");
        variants.addObject().put("key", "off").put("value", false);
        variants.addObject().put("key", "on").put("value", true);
        flag.putArray("rules");
        String checksum = checksum(payload);
        payload.put("checksum", checksum);
        jdbc.update("""
                INSERT INTO configuration_snapshots (
                    id, tenant_id, project_id, environment_id, snapshot_version,
                    schema_version, payload, checksum, generated_at
                ) VALUES (?, ?, ?, ?, ?, 1, CAST(? AS jsonb), ?, ?)
                """, snapshotId, tenantId, projectId, environmentId, version,
                payload.toString(), checksum, java.sql.Timestamp.from(clock.instant()));
        jdbc.update("UPDATE environments SET current_snapshot_version = ?, updated_at = ? WHERE id = ?",
                version, java.sql.Timestamp.from(clock.instant()), environmentId);
        return new SnapshotFixture(snapshotId, version, checksum, payload);
    }

    protected String bearer() {
        return credentialId + "." + secret;
    }

    protected void revokeCredential() {
        jdbc.update("UPDATE service_credentials SET status = 'REVOKED', revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant()), credentialId);
    }

    private void insertScope() {
        var now = java.sql.Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO tenants (id, tenant_key, name, created_at, updated_at) VALUES (?, 'acme', 'Acme', ?, ?)",
                tenantId, now, now);
        jdbc.update("""
                INSERT INTO tenant_members (tenant_id, principal_id, role, created_at, updated_at)
                VALUES (?, 'alice', 'TENANT_OWNER', ?, ?)
                """, tenantId, now, now);
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, project_key, name, created_at, updated_at)
                VALUES (?, ?, 'checkout', 'Checkout', ?, ?)
                """, projectId, tenantId, now, now);
        jdbc.update("""
                INSERT INTO environments (
                    id, tenant_id, project_id, environment_key, name, environment_type,
                    current_snapshot_version, created_at, updated_at
                ) VALUES (?, ?, ?, 'production', 'Production', 'PRODUCTION', 0, ?, ?)
                """, environmentId, tenantId, projectId, now, now);
        jdbc.update("""
                INSERT INTO client_applications (
                    id, tenant_id, project_id, environment_id, client_application_key,
                    lifecycle_status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'orders', 'ACTIVE', ?, ?)
                """, applicationId, tenantId, projectId, environmentId, now, now);
        jdbc.update("""
                INSERT INTO service_credentials (
                    id, tenant_id, project_id, client_application_id, secret_hash,
                    secret_prefix, status, created_at
                ) VALUES (?, ?, ?, ?, ?, 'test', 'ACTIVE', ?)
                """, credentialId, tenantId, projectId, applicationId, passwordEncoder.encode(secret), now);
    }

    private String checksum(JsonNode unsigned) {
        try {
            String canonical = new JsonCanonicalizer(objectMapper.writeValueAsString(unsigned)).getEncodedString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    protected record SnapshotFixture(UUID id, long version, String checksum, ObjectNode payload) {
    }
}
