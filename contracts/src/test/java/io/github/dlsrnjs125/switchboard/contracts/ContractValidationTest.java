package io.github.dlsrnjs125.switchboard.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Iterator;
import java.util.Set;

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
    void validSnapshotFixturePassesDraft202012Schema() throws Exception {
        JsonSchema schema = snapshotSchema();
        JsonNode fixture = JSON.readTree(ROOT.resolve("snapshot-schema/fixtures/valid/full-snapshot.json").toFile());
        assertEquals(Set.of(), schema.validate(fixture));
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
