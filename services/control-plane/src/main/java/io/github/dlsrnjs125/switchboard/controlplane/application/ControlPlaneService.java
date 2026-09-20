package io.github.dlsrnjs125.switchboard.controlplane.application;

import tools.jackson.databind.JsonNode;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Allocation;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Environment;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FeatureFlag;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagLifecycleStatus;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Project;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Tenant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantRole;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.TenantScope;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.infrastructure.UuidV7Generator;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.ScopedFlag;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.ScopedProject;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlPlaneService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$");
    private static final Set<String> OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "GREATER_THAN", "GREATER_THAN_OR_EQUALS",
            "LESS_THAN", "LESS_THAN_OR_EQUALS", "EXISTS", "NOT_EXISTS", "STARTS_WITH", "ENDS_WITH",
            "CONTAINS");

    private final ControlPlaneRepository repository;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ControlPlaneService(ControlPlaneRepository repository, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public Tenant createTenant(String tenantKey, String name, String ownerPrincipalId) {
        validateKey("tenantKey", tenantKey);
        validateName("name", name);
        validateName("ownerPrincipalId", ownerPrincipalId);
        Instant now = clock.instant();
        Tenant tenant = repository.createTenant(ids.next(), tenantKey, name.trim(), now);
        repository.addTenantMember(tenant.id(), ownerPrincipalId.trim(), TenantRole.TENANT_OWNER, now);
        return tenant;
    }

    @Transactional
    public void addTenantMember(String tenantKey, String actorPrincipalId, String principalId, TenantRole role) {
        TenantScope scope = requireScope(tenantKey, actorPrincipalId);
        if (scope.role() != TenantRole.TENANT_OWNER) {
            throw DomainException.forbiddenRole();
        }
        validateName("principalId", principalId);
        repository.addTenantMember(scope.tenantId(), principalId.trim(), role, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Project> listProjects(String tenantKey, String principalId) {
        return repository.listProjects(requireScope(tenantKey, principalId));
    }

    @Transactional
    public Project createProject(String tenantKey, String principalId, CreateProject command) {
        TenantScope scope = requireScope(tenantKey, principalId);
        if (!scope.role().canManageProject()) {
            throw DomainException.forbiddenRole();
        }
        validateKey("projectKey", command.projectKey());
        validateName("name", command.name());
        return repository.createProject(ids.next(), scope, command.projectKey(), command.name().trim(), clock.instant());
    }

    @Transactional
    public Environment createEnvironment(
            String tenantKey, String projectKey, String principalId, CreateEnvironment command) {
        TenantScope scope = requireScope(tenantKey, principalId);
        if (!scope.role().canManageProject()) {
            throw DomainException.forbiddenRole();
        }
        validateKey("projectKey", projectKey);
        validateKey("environmentKey", command.environmentKey());
        validateName("name", command.name());
        if (command.type() == null) {
            throw DomainException.invalid("type", "must not be null");
        }
        ScopedProject project = repository.requireProject(scope, projectKey);
        return repository.createEnvironment(
                ids.next(), scope, project, command.environmentKey(), command.name().trim(), command.type(), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<FeatureFlag> listFlags(String tenantKey, String projectKey, String principalId) {
        TenantScope scope = requireScope(tenantKey, principalId);
        ScopedProject project = repository.requireProject(scope, projectKey);
        return repository.listFlags(scope, project);
    }

    @Transactional
    public FeatureFlag createFlag(String tenantKey, String projectKey, String principalId, CreateFlag command) {
        TenantScope scope = requireAuthorScope(tenantKey, principalId);
        validateKey("projectKey", projectKey);
        validateKey("flagKey", command.flagKey());
        if (command.valueType() == null) {
            throw DomainException.invalid("valueType", "must not be null");
        }
        ScopedProject project = repository.requireProject(scope, projectKey);
        return repository.createFlag(ids.next(), scope, project, command.flagKey(), command.valueType(), clock.instant());
    }

    @Transactional
    public FlagRevision createDraftRevision(
            String tenantKey,
            String projectKey,
            String flagKey,
            String principalId,
            CreateRevision command) {
        TenantScope scope = requireAuthorScope(tenantKey, principalId);
        ScopedProject project = repository.requireProject(scope, projectKey);
        ScopedFlag flag = repository.lockFlag(scope, project, flagKey);
        if (flag.status() == FlagLifecycleStatus.ARCHIVED) {
            throw DomainException.invalid("flagKey", "archived flags cannot be revised");
        }
        validateRevision(flag.valueType(), command);
        long revisionNumber = repository.nextRevisionNumber(flag.id());
        return repository.insertDraft(
                ids.next(), scope, project, flag, revisionNumber, command.defaultVariantKey(),
                command.rolloutSeed(), command.variants(), command.rules(), clock.instant(), ids::next);
    }

    private TenantScope requireScope(String tenantKey, String principalId) {
        validateKey("tenantKey", tenantKey);
        if (principalId == null || principalId.isBlank()) {
            throw DomainException.notFound();
        }
        return repository.findTenantScope(tenantKey, principalId).orElseThrow(DomainException::notFound);
    }

    private TenantScope requireAuthorScope(String tenantKey, String principalId) {
        TenantScope scope = requireScope(tenantKey, principalId);
        if (!scope.role().canAuthorFlag()) {
            throw DomainException.forbiddenRole();
        }
        return scope;
    }

    private void validateRevision(ValueType valueType, CreateRevision command) {
        if (command == null) {
            throw DomainException.invalid("revision", "must not be null");
        }
        validateKey("defaultVariantKey", command.defaultVariantKey());
        validateName("rolloutSeed", command.rolloutSeed());
        if (command.variants().isEmpty()) {
            throw DomainException.invalid("variants", "must contain at least one variant");
        }

        Set<String> variantKeys = new HashSet<>();
        for (Variant variant : command.variants()) {
            validateKey("variants.key", variant.key());
            if (!variantKeys.add(variant.key())) {
                throw DomainException.invalid("variants", "variant keys must be unique");
            }
            if (!matchesType(valueType, variant.value())) {
                throw DomainException.invalid("variants.value", "must match flag valueType " + valueType);
            }
        }
        if (!variantKeys.contains(command.defaultVariantKey())) {
            throw DomainException.invalid("defaultVariantKey", "must reference a declared variant");
        }

        Set<Integer> priorities = new HashSet<>();
        for (Rule rule : command.rules()) {
            if (rule.priority() < 0 || !priorities.add(rule.priority())) {
                throw DomainException.invalid("rules.priority", "must be non-negative and unique");
            }
            if (rule.resultType() == null) {
                throw DomainException.invalid("rules.resultType", "must not be null");
            }
            validateConditions(rule.conditions());
            validateRuleResult(rule, variantKeys);
        }
    }

    private void validateConditions(List<Condition> conditions) {
        Set<Integer> orders = new HashSet<>();
        for (Condition condition : conditions) {
            if (condition.order() < 0 || !orders.add(condition.order())) {
                throw DomainException.invalid("rules.conditions.order", "must be non-negative and unique per rule");
            }
            validateName("rules.conditions.attribute", condition.attribute());
            if (!OPERATORS.contains(condition.operator())) {
                throw DomainException.invalid("rules.conditions.operator", "is not supported");
            }
            JsonNode operand = condition.operand();
            if (condition.operator().equals("EXISTS") || condition.operator().equals("NOT_EXISTS")) {
                if (operand != null && !operand.isNull()) {
                    throw DomainException.invalid("rules.conditions.operand", "must be null for existence operators");
                }
            } else if (operand == null || operand.isNull()) {
                throw DomainException.invalid("rules.conditions.operand", "must not be null");
            } else if ((condition.operator().equals("IN") || condition.operator().equals("NOT_IN"))
                    && (!operand.isArray() || operand.isEmpty())) {
                throw DomainException.invalid("rules.conditions.operand", "must be a non-empty array");
            }
        }
    }

    private void validateRuleResult(Rule rule, Set<String> variantKeys) {
        if (rule.resultType() == RuleResultType.VARIANT) {
            if (!variantKeys.contains(rule.resultVariantKey())) {
                throw DomainException.invalid("rules.resultVariantKey", "must reference a declared variant");
            }
            if (!rule.allocations().isEmpty()) {
                throw DomainException.invalid("rules.allocations", "must be empty for VARIANT rules");
            }
            return;
        }
        if (rule.resultVariantKey() != null) {
            throw DomainException.invalid("rules.resultVariantKey", "must be null for ROLLOUT rules");
        }
        if (rule.allocations().isEmpty()) {
            throw DomainException.invalid("rules.allocations", "must not be empty for ROLLOUT rules");
        }
        Set<String> allocated = new HashSet<>();
        int total = 0;
        for (Allocation allocation : rule.allocations()) {
            if (!variantKeys.contains(allocation.variantKey()) || !allocated.add(allocation.variantKey())) {
                throw DomainException.invalid("rules.allocations.variantKey", "must uniquely reference a declared variant");
            }
            if (allocation.basisPoints() < 1 || allocation.basisPoints() > 10_000) {
                throw DomainException.invalid("rules.allocations.basisPoints", "must be between 1 and 10000");
            }
            total += allocation.basisPoints();
        }
        if (total != 10_000) {
            throw DomainException.invalid("rules.allocations", "basisPoints must total 10000");
        }
    }

    private boolean matchesType(ValueType type, JsonNode value) {
        return value != null && !value.isNull() && switch (type) {
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isTextual();
            case NUMBER -> value.isNumber();
            case OBJECT -> value.isObject();
        };
    }

    private void validateKey(String field, String value) {
        if (value == null || value.length() > 128 || !KEY_PATTERN.matcher(value).matches()) {
            throw DomainException.invalid(field, "must match " + KEY_PATTERN.pattern() + " and be at most 128 characters");
        }
    }

    private void validateName(String field, String value) {
        if (value == null || value.isBlank()) {
            throw DomainException.invalid(field, "must not be blank");
        }
    }
}
