package io.github.dlsrnjs125.switchboard.controlplane.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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
import tools.jackson.databind.JsonNode;

@Component
public class SnapshotValidator {
    private static final String SCHEMA_RESOURCE =
            "/contracts/snapshot-schema/configuration-snapshot-v1.schema.json";
    private static final Set<String> OPERATORS_WITHOUT_OPERAND = Set.of("EXISTS", "NOT_EXISTS");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchema schema;

    public SnapshotValidator() {
        try (InputStream input = SnapshotValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Snapshot Schema v1 resource is missing");
            }
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(objectMapper.readTree(input));
        } catch (IOException exception) {
            throw new IllegalStateException("Snapshot Schema v1 cannot be loaded", exception);
        }
    }

    public void validate(JsonNode payload) {
        try {
            com.fasterxml.jackson.databind.JsonNode snapshot = objectMapper.readTree(payload.toString());
            List<String> violations = new ArrayList<>();
            schema.validate(snapshot).stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .forEach(violations::add);
            validateSemantics(snapshot, violations);
            if (!violations.isEmpty()) {
                throw new IllegalArgumentException(
                        "snapshot contract validation failed: " + String.join("; ", violations));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("snapshot contract validation failed: payload is not valid JSON", exception);
        }
    }

    private void validateSemantics(com.fasterxml.jackson.databind.JsonNode snapshot, List<String> violations) {
        for (com.fasterxml.jackson.databind.JsonNode flag : snapshot.path("flags")) {
            String flagKey = flag.path("flagKey").asText();
            String valueType = flag.path("valueType").asText();
            Map<String, com.fasterxml.jackson.databind.JsonNode> variants = new HashMap<>();
            for (com.fasterxml.jackson.databind.JsonNode variant : flag.path("variants")) {
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
            for (com.fasterxml.jackson.databind.JsonNode rule : flag.path("rules")) {
                int priority = rule.path("priority").asInt();
                if (!priorities.add(priority)) {
                    violations.add(flagKey + ": duplicate rule priority " + priority);
                }
                for (com.fasterxml.jackson.databind.JsonNode condition : rule.path("conditions")) {
                    String operator = condition.path("operator").asText();
                    if (OPERATORS_WITHOUT_OPERAND.contains(operator) && condition.has("operand")) {
                        violations.add(flagKey + ": " + operator + " forbids operand");
                    } else if (!OPERATORS_WITHOUT_OPERAND.contains(operator) && !condition.has("operand")) {
                        violations.add(flagKey + ": " + operator + " requires operand");
                    }
                }

                com.fasterxml.jackson.databind.JsonNode result = rule.path("result");
                if (result.has("variantKey") && !variants.containsKey(result.path("variantKey").asText())) {
                    violations.add(flagKey + ": rule result variantKey does not reference a variant");
                }
                if (result.has("allocations")) {
                    int total = 0;
                    for (com.fasterxml.jackson.databind.JsonNode allocation : result.path("allocations")) {
                        if (!variants.containsKey(allocation.path("variantKey").asText())) {
                            violations.add(flagKey + ": allocation variantKey does not reference a variant");
                        }
                        total += allocation.path("basisPoints").asInt();
                    }
                    if (total != 10_000) {
                        violations.add(flagKey + ": weighted allocation total must equal 10000 basis points");
                    }
                }
            }
        }
        validateChecksum(snapshot, violations);
    }

    private boolean matchesValueType(String valueType, com.fasterxml.jackson.databind.JsonNode value) {
        return switch (valueType) {
            case "BOOLEAN" -> value.isBoolean();
            case "STRING" -> value.isTextual();
            case "NUMBER" -> value.isNumber();
            case "OBJECT" -> value.isObject();
            default -> false;
        };
    }

    private void validateChecksum(com.fasterxml.jackson.databind.JsonNode snapshot, List<String> violations) {
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
                violations.add("checksum does not match the RFC 8785 canonical payload");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("snapshot contract validation failed: canonicalization failed", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
