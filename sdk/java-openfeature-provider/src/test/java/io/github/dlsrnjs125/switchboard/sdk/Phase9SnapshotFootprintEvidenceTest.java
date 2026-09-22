package io.github.dlsrnjs125.switchboard.sdk;

import static io.github.dlsrnjs125.switchboard.evaluation.FlagValue.booleanValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.evaluation.Condition;
import io.github.dlsrnjs125.switchboard.evaluation.ConditionOperator;
import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition;
import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import io.github.dlsrnjs125.switchboard.evaluation.RuleResult;
import io.github.dlsrnjs125.switchboard.evaluation.TargetingRule;
import io.github.dlsrnjs125.switchboard.evaluation.ValueType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("phase9")
class Phase9SnapshotFootprintEvidenceTest {
    @Test
    void recordsRetainedSnapshotHeapForOneHundredAndOneThousandFlags() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int flagCount : List.of(1, 100, 1_000)) {
            SdkSnapshot snapshot = snapshot(flagCount);
            long retainedBytes = GraphLayout.parseInstance(snapshot).totalSize();
            assertTrue(retainedBytes > snapshot.canonicalJson().length);
            results.add(Map.of(
                    "flagCount", flagCount,
                    "retainedBytes", retainedBytes,
                    "canonicalJsonBytes", snapshot.canonicalJson().length));
        }

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.footprint.result",
                "build/reports/phase-09/snapshot-footprint.json"));
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "schemaVersion", 1,
                "measurement", "JOL GraphLayout retained object graph",
                "jdk", System.getProperty("java.runtime.version"),
                "results", results));
    }

    private SdkSnapshot snapshot(int count) {
        Map<String, FlagDefinition> flags = new LinkedHashMap<>();
        StringBuilder syntheticJson = new StringBuilder("{\"flags\":[");
        for (int index = 0; index < count; index++) {
            String key = "flag-" + index;
            flags.put(key, new FlagDefinition(
                    key,
                    ValueType.BOOLEAN,
                    true,
                    "off",
                    "seed-" + index,
                    List.of(new Variant("off", booleanValue(false)), new Variant("on", booleanValue(true))),
                    List.of(new TargetingRule(
                            0,
                            List.of(new Condition("plan", ConditionOperator.EQUALS, "premium")),
                            new RuleResult.Variant("on")))));
            if (index > 0) syntheticJson.append(',');
            syntheticJson.append("{\"flagKey\":\"").append(key).append("\"}");
        }
        syntheticJson.append("]}");
        return new SdkSnapshot(
                UUID.nameUUIDFromBytes(("phase09-footprint-" + count).getBytes(StandardCharsets.UTF_8)),
                1,
                1,
                "synthetic-footprint-only",
                Instant.parse("2026-09-21T00:00:00Z"),
                flags,
                syntheticJson.toString().getBytes(StandardCharsets.UTF_8));
    }
}
