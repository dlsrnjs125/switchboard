package io.github.dlsrnjs125.switchboard.controlplane.domain;

import java.time.Instant;
import java.util.UUID;

public final class DomainTypes {
    private DomainTypes() {
    }

    public enum TenantRole {
        TENANT_OWNER,
        PROJECT_MAINTAINER,
        DEVELOPER,
        VIEWER,
        AUDITOR;

        public boolean canManageProject() {
            return this == TENANT_OWNER || this == PROJECT_MAINTAINER;
        }

        public boolean canAuthorFlag() {
            return canManageProject() || this == DEVELOPER;
        }
    }

    public enum EnvironmentType {
        DEVELOPMENT,
        STAGING,
        PRODUCTION
    }

    public enum ValueType {
        BOOLEAN,
        STRING,
        NUMBER,
        OBJECT
    }

    public enum FlagLifecycleStatus {
        ACTIVE,
        ARCHIVED
    }

    public enum RevisionLifecycleState {
        DRAFT,
        PUBLISHED
    }

    public enum RuleResultType {
        VARIANT,
        ROLLOUT
    }

    public record TenantScope(UUID tenantId, String tenantKey, TenantRole role) {
    }

    public record Project(UUID id, String tenantKey, String projectKey, String name) {
    }

    public record Environment(
            UUID id,
            String environmentKey,
            EnvironmentType type,
            long currentSnapshotVersion) {
    }

    public record FeatureFlag(
            UUID id,
            String flagKey,
            ValueType valueType,
            FlagLifecycleStatus lifecycleStatus) {
    }

    public record FlagRevision(UUID id, long revisionNumber, RevisionLifecycleState lifecycleState) {
    }

    public record Tenant(UUID id, String tenantKey, String name, Instant createdAt) {
    }
}
