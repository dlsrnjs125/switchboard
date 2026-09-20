package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Allocation;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RevisionLifecycleState;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantRole;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.node.JsonNodeFactory;

class ControlPlaneIntegrationTest extends PostgresIntegrationSupport {
    @Test
    void migrationCreatesTheSeventeenBaselineTables() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class);

        assertEquals(17, count);
    }

    @Test
    void createsTenantScopedProjectEnvironmentFlagAndDraftRevision() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject("acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "acme", "checkout", "alice", new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("new.checkout", ValueType.BOOLEAN)));

        CreateRevision command = new CreateRevision(
                List.of(
                        new Variant("on", JsonNodeFactory.instance.booleanNode(true)),
                        new Variant("off", JsonNodeFactory.instance.booleanNode(false))),
                "off",
                "new.checkout:v1",
                List.of(new Rule(
                        0,
                        RuleResultType.ROLLOUT,
                        null,
                        List.of(new Condition(0, "country", "EXISTS", null)),
                        List.of(new Allocation("on", 5_000), new Allocation("off", 5_000)))));

        FlagRevision revision = inTransaction(
                status -> service.createDraftRevision("acme", "checkout", "new.checkout", "alice", command));

        assertEquals(1L, revision.revisionNumber());
        assertEquals(RevisionLifecycleState.DRAFT, revision.lifecycleState());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM flag_variants", Integer.class));
        assertEquals(10_000, jdbc.queryForObject("SELECT sum(basis_points) FROM rollout_allocations", Integer.class));
    }

    @Test
    void hidesCrossTenantResourcesAndRejectsViewerWrites() {
        inTransaction(status -> service.createTenant("tenant-a", "Tenant A", "alice"));
        inTransaction(status -> service.createTenant("tenant-b", "Tenant B", "bob"));
        inTransaction(status -> {
            service.addTenantMember("tenant-a", "alice", "victor", TenantRole.VIEWER);
            return null;
        });
        inTransaction(status -> service.createProject(
                "tenant-b", "bob", new CreateProject("private-project", "Private Project")));

        DomainException crossTenant = assertThrows(
                DomainException.class,
                () -> inTransaction(status -> service.listProjects("tenant-b", "alice")));
        DomainException viewerWrite = assertThrows(
                DomainException.class,
                () -> inTransaction(status -> service.createFlag(
                        "tenant-a", "ignored", "victor", new CreateFlag("flag", ValueType.BOOLEAN))));

        assertEquals("RESOURCE_NOT_FOUND", crossTenant.code());
        assertEquals("RESOURCE_NOT_FOUND", viewerWrite.code());
    }

    @Test
    void rejectsInvalidVariantTypesAndArchivedFlagKeyReuse() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject("acme", "alice", new CreateProject("checkout", "Checkout")));
        var flag = inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("new.checkout", ValueType.BOOLEAN)));

        CreateRevision invalid = new CreateRevision(
                List.of(new Variant("on", JsonNodeFactory.instance.textNode("true"))),
                "on",
                "seed",
                List.of());
        DomainException mismatch = assertThrows(
                DomainException.class,
                () -> inTransaction(status -> service.createDraftRevision(
                        "acme", "checkout", "new.checkout", "alice", invalid)));
        assertEquals("VALIDATION_FAILED", mismatch.code());

        jdbc.update("""
                UPDATE feature_flags
                SET lifecycle_status = 'ARCHIVED', archived_at = now(), updated_at = now()
                WHERE id = ?
                """, flag.id());
        assertThrows(
                DataIntegrityViolationException.class,
                () -> inTransaction(status -> service.createFlag(
                        "acme", "checkout", "alice", new CreateFlag("new.checkout", ValueType.BOOLEAN))));
        assertTrue(jdbc.queryForObject(
                "SELECT lifecycle_status = 'ARCHIVED' FROM feature_flags WHERE id = ?", Boolean.class, flag.id()));
    }
}
