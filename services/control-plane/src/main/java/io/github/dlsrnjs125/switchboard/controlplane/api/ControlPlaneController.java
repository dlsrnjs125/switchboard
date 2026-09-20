package io.github.dlsrnjs125.switchboard.controlplane.api;

import tools.jackson.databind.JsonNode;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Allocation;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Condition;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rule;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Rollback;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Environment;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FeatureFlag;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.FlagRevision;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.Project;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.PublishResult;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.SnapshotSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class ControlPlaneController {
    private final ControlPlaneService service;

    public ControlPlaneController(ControlPlaneService service) {
        this.service = service;
    }

    @GetMapping("/tenants/{tenantKey}/projects")
    public List<Project> listProjects(@PathVariable String tenantKey, Principal principal) {
        return service.listProjects(tenantKey, principal.getName());
    }

    @PostMapping("/tenants/{tenantKey}/projects")
    public ResponseEntity<Project> createProject(
            @PathVariable String tenantKey, Principal principal, @Valid @RequestBody ProjectRequest request) {
        Project project = service.createProject(
                tenantKey, principal.getName(), new CreateProject(request.projectKey(), request.name()));
        return ResponseEntity.created(URI.create("/v1/tenants/" + tenantKey + "/projects/" + project.projectKey()))
                .body(project);
    }

    @PostMapping("/tenants/{tenantKey}/projects/{projectKey}/environments")
    public ResponseEntity<Environment> createEnvironment(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            Principal principal,
            @Valid @RequestBody EnvironmentRequest request) {
        Environment environment = service.createEnvironment(
                tenantKey,
                projectKey,
                principal.getName(),
                new CreateEnvironment(request.environmentKey(), request.name(), request.type()));
        return ResponseEntity.created(URI.create("/v1/tenants/" + tenantKey + "/projects/" + projectKey
                        + "/environments/" + environment.environmentKey()))
                .body(environment);
    }

    @GetMapping("/tenants/{tenantKey}/projects/{projectKey}/flags")
    public List<FeatureFlag> listFlags(
            @PathVariable String tenantKey, @PathVariable String projectKey, Principal principal) {
        return service.listFlags(tenantKey, projectKey, principal.getName());
    }

    @PostMapping("/tenants/{tenantKey}/projects/{projectKey}/flags")
    public ResponseEntity<FeatureFlag> createFlag(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            Principal principal,
            @Valid @RequestBody FlagRequest request) {
        FeatureFlag flag = service.createFlag(
                tenantKey, projectKey, principal.getName(), new CreateFlag(request.flagKey(), request.valueType()));
        return ResponseEntity.created(URI.create("/v1/tenants/" + tenantKey + "/projects/" + projectKey
                        + "/flags/" + flag.flagKey()))
                .body(flag);
    }

    @PostMapping("/tenants/{tenantKey}/projects/{projectKey}/flags/{flagKey}/revisions")
    public ResponseEntity<FlagRevision> createDraftRevision(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            @PathVariable String flagKey,
            Principal principal,
            @Valid @RequestBody RevisionRequest request) {
        CreateRevision command = new CreateRevision(
                request.variants().stream().map(item -> new Variant(item.key(), item.value())).toList(),
                request.defaultVariantKey(),
                request.rolloutSeed(),
                request.rules().stream().map(RuleRequest::toCommand).toList());
        FlagRevision revision = service.createDraftRevision(
                tenantKey, projectKey, flagKey, principal.getName(), command);
        return ResponseEntity.created(URI.create("/v1/tenants/" + tenantKey + "/projects/" + projectKey
                        + "/flags/" + flagKey + "/revisions/" + revision.revisionNumber()))
                .body(revision);
    }

    @PostMapping("/tenants/{tenantKey}/projects/{projectKey}/environments/{environmentKey}/publish")
    public PublishResult publish(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            @PathVariable String environmentKey,
            Principal principal,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody PublishRequest request) {
        return service.publish(
                tenantKey,
                projectKey,
                environmentKey,
                principal.getName(),
                new Publish(
                        request.flagKey(),
                        request.revisionNumber(),
                        request.enabled(),
                        request.expectedEnvironmentVersion(),
                        correlationId(correlationId)));
    }

    @PostMapping("/tenants/{tenantKey}/projects/{projectKey}/environments/{environmentKey}/rollback")
    public PublishResult rollback(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            @PathVariable String environmentKey,
            Principal principal,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody RollbackRequest request) {
        return service.rollback(
                tenantKey,
                projectKey,
                environmentKey,
                principal.getName(),
                new Rollback(
                        request.flagKey(),
                        request.targetRevisionNumber(),
                        request.enabled(),
                        request.expectedEnvironmentVersion(),
                        correlationId(correlationId)));
    }

    @GetMapping("/tenants/{tenantKey}/projects/{projectKey}/environments/{environmentKey}/snapshots/current")
    public SnapshotSummary currentSnapshot(
            @PathVariable String tenantKey,
            @PathVariable String projectKey,
            @PathVariable String environmentKey,
            Principal principal) {
        return service.currentSnapshot(tenantKey, projectKey, environmentKey, principal.getName());
    }

    public record ProjectRequest(@NotBlank @Size(max = 128) String projectKey, @NotBlank @Size(max = 200) String name) {
    }

    public record EnvironmentRequest(
            @NotBlank @Size(max = 128) String environmentKey,
            @NotBlank @Size(max = 200) String name,
            @NotNull EnvironmentType type) {
    }

    public record FlagRequest(@NotBlank @Size(max = 128) String flagKey, @NotNull ValueType valueType) {
    }

    public record RevisionRequest(
            @NotEmpty List<@Valid VariantRequest> variants,
            @NotBlank @Size(max = 128) String defaultVariantKey,
            @NotBlank String rolloutSeed,
            @NotNull List<@Valid RuleRequest> rules) {
    }

    public record VariantRequest(@NotBlank @Size(max = 128) String key, @NotNull JsonNode value) {
    }

    public record RuleRequest(
            @Min(0) int priority,
            @NotNull RuleResultType resultType,
            String resultVariantKey,
            @NotEmpty List<@Valid ConditionRequest> conditions,
            @NotNull List<@Valid AllocationRequest> allocations) {
        Rule toCommand() {
            return new Rule(
                    priority,
                    resultType,
                    resultVariantKey,
                    conditions.stream().map(ConditionRequest::toCommand).toList(),
                    allocations.stream().map(AllocationRequest::toCommand).toList());
        }
    }

    public record ConditionRequest(
            @Min(0) int order,
            @NotBlank @Size(max = 255) String attribute,
            @NotBlank String operator,
            JsonNode operand) {
        Condition toCommand() {
            return new Condition(order, attribute, operator, operand);
        }
    }

    public record AllocationRequest(
            @NotBlank @Size(max = 128) String variantKey,
            @Min(1) int basisPoints) {
        Allocation toCommand() {
            return new Allocation(variantKey, basisPoints);
        }
    }

    public record PublishRequest(
            @NotBlank @Size(max = 128) String flagKey,
            @Min(1) long revisionNumber,
            boolean enabled,
            @Min(0) long expectedEnvironmentVersion) {
    }

    public record RollbackRequest(
            @NotBlank @Size(max = 128) String flagKey,
            @Min(1) long targetRevisionNumber,
            boolean enabled,
            @Min(0) long expectedEnvironmentVersion) {
    }

    private UUID correlationId(String value) {
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Replace invalid caller-provided values with a valid trace identifier.
            }
        }
        return UUID.randomUUID();
    }
}
