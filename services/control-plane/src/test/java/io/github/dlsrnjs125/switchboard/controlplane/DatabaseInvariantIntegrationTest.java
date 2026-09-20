package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.node.JsonNodeFactory;

class DatabaseInvariantIntegrationTest extends PostgresIntegrationSupport {
    @Test
    void publishedRevisionChildrenAreImmutable() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject("acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("new.checkout", ValueType.BOOLEAN)));
        var revision = inTransaction(status -> service.createDraftRevision(
                "acme",
                "checkout",
                "new.checkout",
                "alice",
                new CreateRevision(
                        List.of(new Variant("off", JsonNodeFactory.instance.booleanNode(false))),
                        "off",
                        "seed",
                        List.of())));

        jdbc.update("""
                UPDATE flag_revisions
                SET lifecycle_state = 'PUBLISHED', published_at = now(), updated_at = now()
                WHERE id = ?
                """, revision.id());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE flag_variants SET value = 'true'::jsonb
                WHERE revision_id = ? AND variant_key = 'off'
                """, revision.id()));
    }

    @Test
    void credentialCannotReferenceClientApplicationFromAnotherTenant() {
        var tenantA = inTransaction(status -> service.createTenant("tenant-a", "Tenant A", "alice"));
        var tenantB = inTransaction(status -> service.createTenant("tenant-b", "Tenant B", "bob"));
        var projectA = inTransaction(status -> service.createProject(
                "tenant-a", "alice", new CreateProject("project-a", "Project A")));
        var projectB = inTransaction(status -> service.createProject(
                "tenant-b", "bob", new CreateProject("project-b", "Project B")));
        var environmentB = inTransaction(status -> service.createEnvironment(
                "tenant-b", "project-b", "bob", new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));
        UUID clientB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_applications (
                    id, tenant_id, project_id, environment_id, client_application_key,
                    lifecycle_status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'runtime-b', 'ACTIVE', now(), now())
                """, clientB, tenantB.id(), projectB.id(), environmentB.id());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO service_credentials (
                    id, tenant_id, project_id, client_application_id, secret_hash,
                    secret_prefix, status, created_at
                ) VALUES (?, ?, ?, ?, 'hash', 'sw_test', 'ACTIVE', now())
                """, UUID.randomUUID(), tenantA.id(), projectA.id(), clientB));
    }
}
