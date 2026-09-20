package io.github.dlsrnjs125.switchboard.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DeterministicRolloutTest {
    @Test
    void matchesEveryCanonicalGoldenVector() throws IOException {
        String repositoryRoot = System.getProperty("switchboard.repositoryRoot");
        String json = Files.readString(Path.of(
                repositoryRoot, "contracts", "test-vectors", "sha256-rollout-v1.json"));
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(1, root.get("contractVersion").asInt());
        assertEquals("SHA-256", root.get("algorithm").asString());
        assertEquals(DeterministicRollout.BUCKET_COUNT, root.get("bucketCount").asInt());

        DeterministicRollout rollout = new DeterministicRollout();
        List<String> verified = new ArrayList<>();

        for (JsonNode vector : root.get("vectors")) {
            String targetingKey = vector.get("targetingKey").asString();
            String flagKey = vector.get("flagKey").asString();
            String seed = vector.get("rolloutSeed").asString();
            assertEquals(vector.get("preimageHex").asString(), rollout.preimageHex(targetingKey, flagKey, seed));
            assertEquals(vector.get("digestHex").asString(), rollout.digestHex(targetingKey, flagKey, seed));
            assertEquals(vector.get("bucket").asInt(), rollout.bucket(targetingKey, flagKey, seed));
            verified.add(targetingKey);
        }

        assertEquals(3, verified.size(), "all canonical vectors must be exercised");
    }

    @Test
    void producesTheSameBucketAcrossIndependentInstances() {
        int first = new DeterministicRollout().bucket("alice", "checkout-v2", "checkout-seed");
        int restarted = new DeterministicRollout().bucket("alice", "checkout-v2", "checkout-seed");

        assertEquals(first, restarted);
        assertTrue(first >= 0 && first < DeterministicRollout.BUCKET_COUNT);
    }
}
