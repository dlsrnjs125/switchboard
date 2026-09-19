package io.github.dlsrnjs125.switchboard.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ContractValidationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("contract.root"));

    @Test
    void openApiIsVersionedAndAllLocalReferencesResolve() throws Exception {
        JsonNode api = new ObjectMapper(new YAMLFactory()).readTree(ROOT.resolve("openapi/control-plane-v1.yaml").toFile());
        assertEquals("3.1.0", api.path("openapi").asText());
        assertEquals("1.0.0", api.at("/info/version").asText());
        assertTrue(api.path("paths").size() >= 5);
        assertTrue(api.at("/components/schemas/ErrorResponse/required").isArray());
        assertTrue(api.at("/components/responses/VersionConflict").isObject());
        assertLocalReferencesResolve(api, api);
        assertOperationsAreExecutable(api.path("paths"));

        JsonNode createFlag = api.at("/components/schemas/CreateFlagRequest");
        assertEquals(Set.of("flagKey", "valueType"), requiredFields(createFlag));
        assertFalse(createFlag.path("properties").has("variants"));
        assertFalse(createFlag.path("properties").has("defaultVariantKey"));

        JsonNode createRevision = api.at("/components/schemas/CreateRevisionRequest");
        assertFalse(createRevision.path("properties").has("enabled"));
        assertTrue(requiredFields(createRevision).contains("rolloutSeed"));
        assertTrue(requiredFields(api.at("/components/schemas/PublishRequest")).contains("enabled"));
        assertTrue(requiredFields(api.at("/components/schemas/RollbackRequest")).contains("enabled"));
    }

    @Test
    void openApiPassesTheOfficialParser() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        var result = new OpenAPIV3Parser().readLocation(
                ROOT.resolve("openapi/control-plane-v1.yaml").toString(), null, options);
        assertNotNull(result.getOpenAPI(), () -> "Parser messages: " + result.getMessages());
        assertTrue(result.getMessages().isEmpty(), () -> "Parser messages: " + result.getMessages());
    }

    @Test
    void validSnapshotFixturePassesSchemaAndSemanticValidation() throws Exception {
        JsonSchema schema = snapshotSchema();
        JsonNode fixture = JSON.readTree(ROOT.resolve("snapshot-schema/fixtures/valid/full-snapshot.json").toFile());
        assertEquals(Set.of(), schema.validate(fixture));
        assertEquals(List.of(), validateSnapshotSemantics(fixture));
    }

    @Test
    void everyInvalidSnapshotFixtureFails() throws Exception {
        JsonSchema schema = snapshotSchema();
        try (var files = Files.list(ROOT.resolve("snapshot-schema/fixtures/invalid"))) {
            var fixtures = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertFalse(fixtures.isEmpty());
            for (Path fixture : fixtures) {
                Set<ValidationMessage> errors = schema.validate(JSON.readTree(fixture.toFile()));
                assertFalse(errors.isEmpty(), () -> fixture + " unexpectedly passed");
            }
        }
    }

    @Test
    void snapshotSemanticValidationRejectsCanonicalInvariantViolations() throws Exception {
        assertSemanticViolation("variant value type", snapshot ->
                firstVariant(snapshot).put("value", "not-a-boolean"));
        assertSemanticViolation("defaultVariantKey", snapshot ->
                firstFlag(snapshot).put("defaultVariantKey", "missing"));
        assertSemanticViolation("rule result variantKey", snapshot ->
                firstResult(snapshot).put("variantKey", "missing"));
        assertSemanticViolation("allocation variantKey", snapshot -> {
            ObjectNode result = firstResult(snapshot);
            result.remove("variantKey");
            result.putArray("allocations").addObject()
                    .put("variantKey", "missing").put("basisPoints", 10_000);
        });
        assertSemanticViolation("weighted allocation total", snapshot -> {
            ObjectNode result = firstResult(snapshot);
            result.remove("variantKey");
            ArrayNode allocations = result.putArray("allocations");
            allocations.addObject().put("variantKey", "off").put("basisPoints", 4_000);
            allocations.addObject().put("variantKey", "on").put("basisPoints", 4_000);
        });
        assertSemanticViolation("duplicate rule priority", snapshot -> {
            ArrayNode rules = (ArrayNode) firstFlag(snapshot).path("rules");
            rules.add(rules.get(0).deepCopy());
        });
        assertSemanticViolation("requires operand", snapshot ->
                firstCondition(snapshot).remove("operand"));
        assertSemanticViolation("forbids operand", snapshot -> {
            ObjectNode condition = firstCondition(snapshot);
            condition.put("operator", "EXISTS");
            condition.put("operand", "unexpected");
        });
    }

    @Test
    void snapshotSchemaAlsoEnforcesOperatorOperandShape() throws Exception {
        JsonNode missingOperand = validSnapshot();
        firstCondition(missingOperand).remove("operand");
        assertFalse(snapshotSchema().validate(missingOperand).isEmpty());

        JsonNode forbiddenOperand = validSnapshot();
        firstCondition(forbiddenOperand).put("operator", "NOT_EXISTS");
        assertFalse(snapshotSchema().validate(forbiddenOperand).isEmpty());
    }

    @Test
    void validSnapshotChecksumMatchesRfc8785PayloadWithoutChecksum() throws Exception {
        JsonNode fixture = JSON.readTree(ROOT.resolve("snapshot-schema/fixtures/valid/full-snapshot.json").toFile());
        String expected = fixture.path("checksum").asText();
        var payload = fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("checksum");
        String canonical = new JsonCanonicalizer(JSON.writeValueAsString(payload)).getEncodedString();
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, actual);
    }

    @Test
    void rolloutGoldenVectorsFixEncodingDigestAndBucket() throws Exception {
        JsonNode document = JSON.readTree(ROOT.resolve("test-vectors/sha256-rollout-v1.json").toFile());
        assertEquals(1, document.path("contractVersion").asInt());
        assertEquals("SHA-256", document.path("algorithm").asText());
        int bucketCount = document.path("bucketCount").asInt();
        assertEquals(10_000, bucketCount);

        for (JsonNode vector : document.path("vectors")) {
            byte[] preimage = encode(
                    vector.path("targetingKey").asText(),
                    vector.path("flagKey").asText(),
                    vector.path("rolloutSeed").asText());
            assertEquals(vector.path("preimageHex").asText(), HexFormat.of().formatHex(preimage));

            byte[] digest = MessageDigest.getInstance("SHA-256").digest(preimage);
            assertEquals(vector.path("digestHex").asText(), HexFormat.of().formatHex(digest));

            byte[] firstEight = java.util.Arrays.copyOf(digest, 8);
            BigInteger unsigned = new BigInteger(1, firstEight);
            assertEquals(vector.path("unsigned64").asText(), unsigned.toString());
            assertEquals(vector.path("bucket").asInt(), unsigned.mod(BigInteger.valueOf(bucketCount)).intValueExact());
        }
    }

    private static JsonSchema snapshotSchema() throws IOException {
        JsonNode schemaNode = JSON.readTree(ROOT.resolve("snapshot-schema/configuration-snapshot-v1.schema.json").toFile());
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    private static List<String> validateSnapshotSemantics(JsonNode snapshot) {
        List<String> errors = new ArrayList<>();
        for (JsonNode flag : snapshot.path("flags")) {
            String flagKey = flag.path("flagKey").asText();
            String valueType = flag.path("valueType").asText();
            Map<String, JsonNode> variants = new HashMap<>();
            for (JsonNode variant : flag.path("variants")) {
                String variantKey = variant.path("key").asText();
                if (variants.putIfAbsent(variantKey, variant.path("value")) != null) {
                    errors.add(flagKey + ": duplicate variant key " + variantKey);
                }
                if (!matchesValueType(valueType, variant.path("value"))) {
                    errors.add(flagKey + ": variant value type does not match " + valueType);
                }
            }

            String defaultVariantKey = flag.path("defaultVariantKey").asText();
            if (!variants.containsKey(defaultVariantKey)) {
                errors.add(flagKey + ": defaultVariantKey does not reference a variant");
            }

            Set<Integer> priorities = new HashSet<>();
            for (JsonNode rule : flag.path("rules")) {
                int priority = rule.path("priority").asInt();
                if (!priorities.add(priority)) {
                    errors.add(flagKey + ": duplicate rule priority " + priority);
                }

                for (JsonNode condition : rule.path("conditions")) {
                    String operator = condition.path("operator").asText();
                    boolean operandPresent = condition.has("operand");
                    if (Set.of("EXISTS", "NOT_EXISTS").contains(operator)) {
                        if (operandPresent) errors.add(flagKey + ": " + operator + " forbids operand");
                    } else if (!operandPresent) {
                        errors.add(flagKey + ": " + operator + " requires operand");
                    }
                }

                JsonNode result = rule.path("result");
                if (result.has("variantKey") && !variants.containsKey(result.path("variantKey").asText())) {
                    errors.add(flagKey + ": rule result variantKey does not reference a variant");
                }
                if (result.has("allocations")) {
                    int total = 0;
                    for (JsonNode allocation : result.path("allocations")) {
                        String variantKey = allocation.path("variantKey").asText();
                        if (!variants.containsKey(variantKey)) {
                            errors.add(flagKey + ": allocation variantKey does not reference a variant");
                        }
                        total += allocation.path("basisPoints").asInt();
                    }
                    if (total != 10_000) {
                        errors.add(flagKey + ": weighted allocation total must equal 10000 basis points");
                    }
                }
            }
        }
        return errors;
    }

    private static boolean matchesValueType(String valueType, JsonNode value) {
        return switch (valueType) {
            case "BOOLEAN" -> value.isBoolean();
            case "STRING" -> value.isTextual();
            case "NUMBER" -> value.isNumber();
            case "OBJECT" -> value.isObject();
            default -> false;
        };
    }

    private static void assertSemanticViolation(String expectedMessage, Consumer<JsonNode> mutation) throws Exception {
        JsonNode snapshot = validSnapshot();
        mutation.accept(snapshot);
        List<String> errors = validateSnapshotSemantics(snapshot);
        assertTrue(errors.stream().anyMatch(error -> error.contains(expectedMessage)),
                () -> "Expected semantic error containing '" + expectedMessage + "' but got " + errors);
    }

    private static JsonNode validSnapshot() throws IOException {
        return JSON.readTree(ROOT.resolve("snapshot-schema/fixtures/valid/full-snapshot.json").toFile());
    }

    private static ObjectNode firstFlag(JsonNode snapshot) {
        return (ObjectNode) snapshot.path("flags").get(0);
    }

    private static ObjectNode firstVariant(JsonNode snapshot) {
        return (ObjectNode) firstFlag(snapshot).path("variants").get(0);
    }

    private static ObjectNode firstResult(JsonNode snapshot) {
        return (ObjectNode) firstFlag(snapshot).path("rules").get(0).path("result");
    }

    private static ObjectNode firstCondition(JsonNode snapshot) {
        return (ObjectNode) firstFlag(snapshot).path("rules").get(0).path("conditions").get(0);
    }

    private static Set<String> requiredFields(JsonNode schema) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        return required;
    }

    private static byte[] encode(String... fields) {
        int size = 0;
        byte[][] values = new byte[fields.length][];
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].getBytes(StandardCharsets.UTF_8);
            size += Integer.BYTES + values[i].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] value : values) buffer.putInt(value.length).put(value);
        return buffer.array();
    }

    private static void assertLocalReferencesResolve(JsonNode root, JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null) {
                String value = ref.asText();
                assertTrue(value.startsWith("#/"), () -> "Only local refs are allowed: " + value);
                assertFalse(root.at(value.substring(1)).isMissingNode(), () -> "Unresolved ref: " + value);
            }
            node.fields().forEachRemaining(entry -> assertLocalReferencesResolve(root, entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(child -> assertLocalReferencesResolve(root, child));
        }
    }

    private static void assertOperationsAreExecutable(JsonNode paths) {
        Iterator<java.util.Map.Entry<String, JsonNode>> entries = paths.fields();
        while (entries.hasNext()) {
            var path = entries.next();
            assertTrue(path.getKey().startsWith("/"));
            path.getValue().fields().forEachRemaining(operation -> {
                if (Set.of("get", "post", "put", "patch", "delete").contains(operation.getKey())) {
                    assertFalse(operation.getValue().path("operationId").asText().isBlank(), path.getKey());
                    assertTrue(operation.getValue().path("responses").isObject(), path.getKey());
                }
            });
        }
    }
}
