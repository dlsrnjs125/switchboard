package io.github.dlsrnjs125.switchboard.distribution.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

@Component
public class DistributionSnapshotValidator {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String SCHEMA_RESOURCE =
            "/contracts/snapshot-schema/configuration-snapshot-v1.schema.json";
    private static final Set<String> NO_OPERAND = Set.of("EXISTS", "NOT_EXISTS");

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    public DistributionSnapshotValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = DistributionSnapshotValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Snapshot Schema v1 resource is missing");
            }
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(objectMapper.readTree(input));
        } catch (IOException exception) {
            throw new IllegalStateException("Snapshot Schema v1 cannot be loaded", exception);
        }
    }

    public void validate(SnapshotArtifact artifact) {
        List<String> violations = new ArrayList<>();
        JsonNode snapshot = artifact.payload();
        if (artifact.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            violations.add("unsupported schema version " + artifact.schemaVersion());
        }
        if (snapshot.path("snapshotVersion").asLong() != artifact.snapshotVersion()) {
            violations.add("payload snapshotVersion differs from relational metadata");
        }
        if (!artifact.checksum().equals(snapshot.path("checksum").asText())) {
            violations.add("payload checksum differs from relational metadata");
        }
        schema.validate(snapshot).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .forEach(violations::add);
        validateSemantics(snapshot, violations);
        validateChecksum(snapshot, violations);
        if (!violations.isEmpty()) {
            throw new SnapshotIntegrityException(String.join("; ", violations));
        }
    }

    private void validateSemantics(JsonNode snapshot, List<String> violations) {
        for (JsonNode flag : snapshot.path("flags")) {
            String flagKey = flag.path("flagKey").asText();
            String valueType = flag.path("valueType").asText();
            Map<String, JsonNode> variants = new HashMap<>();
            for (JsonNode variant : flag.path("variants")) {
                String variantKey = variant.path("key").asText();
                if (variants.putIfAbsent(variantKey, variant.path("value")) != null) {
                    violations.add(flagKey + ": duplicate variant key " + variantKey);
                }
                if (!matchesValueType(valueType, variant.path("value"))) {
                    violations.add(flagKey + ": variant value type does not match " + valueType);
                }
            }
            if (!variants.containsKey(flag.path("defaultVariantKey").asText())) {
                violations.add(flagKey + ": defaultVariantKey does not reference a variant");
            }
            Set<Integer> priorities = new HashSet<>();
            for (JsonNode rule : flag.path("rules")) {
                int priority = rule.path("priority").asInt();
                if (!priorities.add(priority)) {
                    violations.add(flagKey + ": duplicate rule priority " + priority);
                }
                for (JsonNode condition : rule.path("conditions")) {
                    String operator = condition.path("operator").asText();
                    if (NO_OPERAND.contains(operator) && condition.has("operand")) {
                        violations.add(flagKey + ": " + operator + " forbids operand");
                    } else if (!NO_OPERAND.contains(operator) && !condition.has("operand")) {
                        violations.add(flagKey + ": " + operator + " requires operand");
                    }
                }
                JsonNode result = rule.path("result");
                if (result.has("variantKey") && !variants.containsKey(result.path("variantKey").asText())) {
                    violations.add(flagKey + ": result variantKey does not reference a variant");
                }
                if (result.has("allocations")) {
                    int total = 0;
                    for (JsonNode allocation : result.path("allocations")) {
                        if (!variants.containsKey(allocation.path("variantKey").asText())) {
                            violations.add(flagKey + ": allocation variantKey does not reference a variant");
                        }
                        total += allocation.path("basisPoints").asInt();
                    }
                    if (total != 10_000) {
                        violations.add(flagKey + ": allocation total must equal 10000");
                    }
                }
            }
        }
    }

    private boolean matchesValueType(String valueType, JsonNode value) {
        return switch (valueType) {
            case "BOOLEAN" -> value.isBoolean();
            case "STRING" -> value.isTextual();
            case "NUMBER" -> value.isNumber();
            case "OBJECT" -> value.isObject();
            default -> false;
        };
    }

    private void validateChecksum(JsonNode snapshot, List<String> violations) {
        if (!(snapshot instanceof ObjectNode object) || !snapshot.path("checksum").isTextual()) {
            return;
        }
        String expected = snapshot.path("checksum").asText();
        ObjectNode unsigned = object.deepCopy();
        unsigned.remove("checksum");
        try {
            String canonical = new JsonCanonicalizer(objectMapper.writeValueAsString(unsigned)).getEncodedString();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(actual)) {
                violations.add("checksum does not match RFC 8785 canonical payload");
            }
        } catch (IOException exception) {
            throw new SnapshotIntegrityException("snapshot canonicalization failed", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
