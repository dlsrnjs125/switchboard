package io.github.dlsrnjs125.switchboard.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DeterministicRolloutTest {
    private static final Pattern VECTOR = Pattern.compile(
            "\\{\\\"targetingKey\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"flagKey\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"rolloutSeed\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"preimageHex\\\":\\\"([a-f0-9]+)\\\","
                    + "\\\"digestHex\\\":\\\"([a-f0-9]+)\\\","
                    + "\\\"unsigned64\\\":\\\"[0-9]+\\\","
                    + "\\\"bucket\\\":([0-9]+)\\}");

    @Test
    void matchesEveryCanonicalGoldenVector() throws IOException {
        String repositoryRoot = System.getProperty("switchboard.repositoryRoot");
        String json = Files.readString(Path.of(
                repositoryRoot, "contracts", "test-vectors", "sha256-rollout-v1.json"));
        DeterministicRollout rollout = new DeterministicRollout();
        List<String> verified = new ArrayList<>();
        Matcher matcher = VECTOR.matcher(json);

        while (matcher.find()) {
            String targetingKey = matcher.group(1);
            String flagKey = matcher.group(2);
            String seed = matcher.group(3);
            assertEquals(matcher.group(4), rollout.preimageHex(targetingKey, flagKey, seed));
            assertEquals(matcher.group(5), rollout.digestHex(targetingKey, flagKey, seed));
            assertEquals(Integer.parseInt(matcher.group(6)), rollout.bucket(targetingKey, flagKey, seed));
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
