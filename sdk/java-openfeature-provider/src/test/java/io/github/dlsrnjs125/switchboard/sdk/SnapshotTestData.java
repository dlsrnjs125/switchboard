package io.github.dlsrnjs125.switchboard.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;

final class SnapshotTestData {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SnapshotTestData() {
    }

    static FullSnapshot snapshot(long version, Instant generatedAt, boolean defaultEnabled) {
        return snapshot(UUID.randomUUID(), version, generatedAt, defaultEnabled);
    }

    static FullSnapshot snapshot(UUID id, long version, Instant generatedAt, boolean defaultEnabled) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("snapshotId", id.toString());
            root.put("snapshotVersion", version);
            root.put("tenantKey", "acme");
            root.put("projectKey", "checkout");
            root.put("environmentKey", "production");
            root.put("generatedAt", generatedAt.toString());
            ArrayNode flags = root.putArray("flags");
            ObjectNode flag = flags.addObject();
            flag.put("flagKey", "checkout-v2");
            flag.put("revisionNumber", version);
            flag.put("valueType", "BOOLEAN");
            flag.put("enabled", true);
            flag.put("defaultVariantKey", defaultEnabled ? "on" : "off");
            flag.put("rolloutSeed", "checkout-seed");
            ArrayNode variants = flag.putArray("variants");
            variants.addObject().put("key", "off").put("value", false);
            variants.addObject().put("key", "on").put("value", true);
            ObjectNode rule = flag.putArray("rules").addObject();
            rule.put("priority", 10);
            rule.putArray("conditions").addObject()
                    .put("attribute", "plan").put("operator", "EQUALS").put("operand", "premium");
            rule.putObject("result").put("variantKey", "on");
            addSimpleFlag(flags, "banner", "STRING", "default", "welcome");
            addSimpleFlag(flags, "max-items", "NUMBER", "default", 25);
            ObjectNode objectValue = MAPPER.createObjectNode().put("color", "blue");
            addSimpleFlag(flags, "theme", "OBJECT", "default", objectValue);
            String unsigned = new JsonCanonicalizer(MAPPER.writeValueAsString(root)).getEncodedString();
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(unsigned.getBytes(StandardCharsets.UTF_8)));
            root.put("checksum", checksum);
            String canonical = new JsonCanonicalizer(MAPPER.writeValueAsString(root)).getEncodedString();
            return FullSnapshot.newBuilder()
                    .setSnapshotVersion(version)
                    .setSchemaVersion(1)
                    .setChecksum(checksum)
                    .setCanonicalJson(ByteString.copyFromUtf8(canonical))
                    .setDeliveryId(UUID.randomUUID().toString())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static FullSnapshot corruptChecksum(FullSnapshot valid) {
        return valid.toBuilder().setChecksum("f".repeat(64)).build();
    }

    private static void addSimpleFlag(
            ArrayNode flags, String flagKey, String valueType, String variantKey, Object value) {
        ObjectNode flag = flags.addObject();
        flag.put("flagKey", flagKey);
        flag.put("revisionNumber", 1);
        flag.put("valueType", valueType);
        flag.put("enabled", true);
        flag.put("defaultVariantKey", variantKey);
        flag.put("rolloutSeed", flagKey + "-seed");
        ObjectNode variant = flag.putArray("variants").addObject().put("key", variantKey);
        variant.set("value", MAPPER.valueToTree(value));
        flag.putArray("rules");
    }
}
