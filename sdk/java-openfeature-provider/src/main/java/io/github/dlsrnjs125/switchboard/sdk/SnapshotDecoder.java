package io.github.dlsrnjs125.switchboard.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import io.github.dlsrnjs125.switchboard.evaluation.Condition;
import io.github.dlsrnjs125.switchboard.evaluation.ConditionOperator;
import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition;
import io.github.dlsrnjs125.switchboard.evaluation.FlagValue;
import io.github.dlsrnjs125.switchboard.evaluation.RuleResult;
import io.github.dlsrnjs125.switchboard.evaluation.TargetingRule;
import io.github.dlsrnjs125.switchboard.evaluation.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;

public final class SnapshotDecoder {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final String SCHEMA_RESOURCE =
            "/contracts/snapshot-schema/configuration-snapshot-v1.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    public SnapshotDecoder() {
        this(new ObjectMapper());
    }

    public SnapshotDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = SnapshotDecoder.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Snapshot Schema v1 resource is missing");
            }
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(objectMapper.readTree(input));
        } catch (IOException exception) {
            throw new IllegalStateException("Snapshot Schema v1 cannot be loaded", exception);
        }
    }

    public SdkSnapshot decode(FullSnapshot message) {
        SdkSnapshot snapshot = decode(message.getCanonicalJson().toByteArray());
        if (snapshot.snapshotVersion() != message.getSnapshotVersion()) {
            throw new SnapshotIntegrityException("gRPC and payload snapshot versions differ");
        }
        if (snapshot.schemaVersion() != message.getSchemaVersion()) {
            throw new SnapshotIntegrityException("gRPC and payload schema versions differ");
        }
        if (!snapshot.checksum().equals(message.getChecksum())) {
            throw new SnapshotIntegrityException("gRPC and payload checksums differ");
        }
        return snapshot;
    }

    public SdkSnapshot decode(byte[] canonicalJson) {
        try {
            JsonNode root = objectMapper.readTree(canonicalJson);
            List<String> violations = schema.validate(root).stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .toList();
            if (!violations.isEmpty()) {
                throw new SnapshotIntegrityException(String.join("; ", violations));
            }
            if (root.path("schemaVersion").asInt() != SUPPORTED_SCHEMA_VERSION) {
                throw new SnapshotIntegrityException(
                        "unsupported schema version " + root.path("schemaVersion").asInt());
            }
            validateChecksum(root);
            Map<String, FlagDefinition> flags = new LinkedHashMap<>();
            for (JsonNode flagNode : root.path("flags")) {
                FlagDefinition flag = mapFlag(flagNode);
                if (flags.putIfAbsent(flag.flagKey(), flag) != null) {
                    throw new SnapshotIntegrityException("duplicate flag key " + flag.flagKey());
                }
            }
            return new SdkSnapshot(
                    UUID.fromString(root.path("snapshotId").asText()),
                    root.path("snapshotVersion").asLong(),
                    root.path("schemaVersion").asInt(),
                    root.path("checksum").asText(),
                    Instant.parse(root.path("generatedAt").asText()),
                    flags,
                    canonicalJson);
        } catch (SnapshotIntegrityException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new SnapshotIntegrityException("snapshot cannot be decoded", exception);
        }
    }

    private FlagDefinition mapFlag(JsonNode node) {
        ValueType valueType = ValueType.valueOf(node.path("valueType").asText());
        List<FlagDefinition.Variant> variants = new ArrayList<>();
        for (JsonNode variant : node.path("variants")) {
            variants.add(new FlagDefinition.Variant(
                    variant.path("key").asText(), mapValue(valueType, variant.path("value"))));
        }
        List<TargetingRule> rules = new ArrayList<>();
        for (JsonNode rule : node.path("rules")) {
            List<Condition> conditions = new ArrayList<>();
            for (JsonNode condition : rule.path("conditions")) {
                Object operand = condition.has("operand") ? toJava(condition.path("operand")) : null;
                conditions.add(new Condition(
                        condition.path("attribute").asText(),
                        ConditionOperator.parse(condition.path("operator").asText()),
                        operand));
            }
            rules.add(new TargetingRule(
                    rule.path("priority").asInt(), conditions, mapResult(rule.path("result"))));
        }
        return new FlagDefinition(
                node.path("flagKey").asText(),
                valueType,
                node.path("enabled").asBoolean(),
                node.path("defaultVariantKey").asText(),
                node.path("rolloutSeed").asText(),
                variants,
                rules);
    }

    private FlagValue mapValue(ValueType type, JsonNode value) {
        return switch (type) {
            case BOOLEAN -> new FlagValue.BooleanValue(value.asBoolean());
            case STRING -> new FlagValue.StringValue(value.asText());
            case NUMBER -> new FlagValue.NumberValue(value.decimalValue());
            case OBJECT -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> object = objectMapper.convertValue(value, Map.class);
                yield new FlagValue.ObjectValue(object);
            }
        };
    }

    private RuleResult mapResult(JsonNode result) {
        if (result.has("variantKey")) {
            return new RuleResult.Variant(result.path("variantKey").asText());
        }
        List<RuleResult.Allocation> allocations = new ArrayList<>();
        for (JsonNode allocation : result.path("allocations")) {
            allocations.add(new RuleResult.Allocation(
                    allocation.path("variantKey").asText(), allocation.path("basisPoints").asInt()));
        }
        return new RuleResult.Rollout(allocations);
    }

    private Object toJava(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return objectMapper.convertValue(value, Object.class);
    }

    private void validateChecksum(JsonNode root) throws IOException {
        if (!(root instanceof ObjectNode object)) {
            throw new SnapshotIntegrityException("snapshot root must be an object");
        }
        String expected = root.path("checksum").asText();
        ObjectNode unsigned = object.deepCopy();
        unsigned.remove("checksum");
        try {
            String canonical = new JsonCanonicalizer(objectMapper.writeValueAsString(unsigned)).getEncodedString();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(actual)) {
                throw new SnapshotIntegrityException("checksum does not match RFC 8785 canonical payload");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }
}
