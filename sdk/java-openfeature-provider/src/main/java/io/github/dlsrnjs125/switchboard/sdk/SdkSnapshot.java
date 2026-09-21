package io.github.dlsrnjs125.switchboard.sdk;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SdkSnapshot(
        UUID snapshotId,
        long snapshotVersion,
        int schemaVersion,
        String checksum,
        Instant generatedAt,
        Map<String, FlagDefinition> flags,
        byte[] canonicalJson) {
    public SdkSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (snapshotVersion < 1) {
            throw new IllegalArgumentException("snapshotVersion must be positive");
        }
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(generatedAt, "generatedAt");
        flags = Map.copyOf(flags);
        canonicalJson = canonicalJson.clone();
    }

    @Override
    public byte[] canonicalJson() {
        return canonicalJson.clone();
    }

    public Optional<FlagDefinition> flag(String key) {
        return Optional.ofNullable(flags.get(key));
    }
}
