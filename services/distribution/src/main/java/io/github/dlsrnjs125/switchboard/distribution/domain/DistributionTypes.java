package io.github.dlsrnjs125.switchboard.distribution.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class DistributionTypes {
    private DistributionTypes() {
    }

    public record EnvironmentScope(
            UUID tenantId,
            UUID projectId,
            UUID environmentId,
            String tenantKey,
            String projectKey,
            String environmentKey) {
    }

    public record CredentialPrincipal(
            UUID credentialId,
            UUID clientApplicationId,
            String clientApplicationKey,
            EnvironmentScope scope) {
    }

    public record SnapshotArtifact(
            UUID snapshotId,
            EnvironmentScope scope,
            long snapshotVersion,
            int schemaVersion,
            String checksum,
            byte[] canonicalJson,
            JsonNode payload,
            Instant generatedAt) {
        public SnapshotArtifact {
            canonicalJson = canonicalJson.clone();
        }

        @Override
        public byte[] canonicalJson() {
            return canonicalJson.clone();
        }
    }

    public record SnapshotNotification(
            UUID eventId,
            String tenantKey,
            String projectKey,
            String environmentKey,
            UUID snapshotId,
            long snapshotVersion,
            String checksum) {
    }
}
