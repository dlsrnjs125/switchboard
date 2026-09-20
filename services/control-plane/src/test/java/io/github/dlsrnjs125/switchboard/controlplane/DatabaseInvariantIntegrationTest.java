package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
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

    @Test
    void rolloutRuleWithoutAllocationsCannotCommit() {
        var revision = createBooleanDraft(List.of());
        UUID ruleId = UUID.randomUUID();

        assertThrows(TransactionSystemException.class, () -> inTransaction(status -> {
            jdbc.update("""
                    INSERT INTO targeting_rules (id, revision_id, priority, result_type, result_variant_key)
                    VALUES (?, ?, 0, 'ROLLOUT', NULL)
                    """, ruleId, revision.id());
            jdbc.update("""
                    INSERT INTO rule_conditions (id, rule_id, condition_order, attribute, operator, operand)
                    VALUES (?, ?, 0, 'country', 'EXISTS', NULL)
                    """, UUID.randomUUID(), ruleId);
            return null;
        }));
    }

    @Test
    void variantRuleWithAllocationCannotCommit() {
        var revision = createBooleanDraft(List.of(new Rule(
                0,
                RuleResultType.VARIANT,
                "on",
                List.of(new Condition(0, "country", "EXISTS", null)),
                List.of())));
        UUID ruleId = jdbc.queryForObject(
                "SELECT id FROM targeting_rules WHERE revision_id = ?", UUID.class, revision.id());

        assertThrows(TransactionSystemException.class, () -> inTransaction(status -> {
            jdbc.update("""
                    INSERT INTO rollout_allocations (rule_id, variant_key, revision_id, basis_points)
                    VALUES (?, 'on', ?, 10000)
                    """, ruleId, revision.id());
            return null;
        }));
    }

    @Test
    void targetingRuleWithoutConditionsCannotCommit() {
        var revision = createBooleanDraft(List.of());

        assertThrows(TransactionSystemException.class, () -> inTransaction(status -> {
            jdbc.update("""
                    INSERT INTO targeting_rules (id, revision_id, priority, result_type, result_variant_key)
                    VALUES (?, ?, 0, 'VARIANT', 'on')
                    """, UUID.randomUUID(), revision.id());
            return null;
        }));
    }

    private io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision createBooleanDraft(
            List<Rule> rules) {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject("acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("new.checkout", ValueType.BOOLEAN)));
        return inTransaction(status -> service.createDraftRevision(
                "acme",
                "checkout",
                "new.checkout",
                "alice",
                new CreateRevision(
                        List.of(
                                new Variant("on", JsonNodeFactory.instance.booleanNode(true)),
                                new Variant("off", JsonNodeFactory.instance.booleanNode(false))),
                        "off",
                        "seed",
                        rules)));
    }
}
