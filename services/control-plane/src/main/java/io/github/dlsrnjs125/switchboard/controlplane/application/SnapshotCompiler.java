package io.github.dlsrnjs125.switchboard.controlplane.application;

import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublicationFlag;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedAllocation;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedCondition;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedRule;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SnapshotCompiler {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public SnapshotCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledSnapshot compile(
            UUID snapshotId,
            long snapshotVersion,
            String tenantKey,
            String projectKey,
            String environmentKey,
            Instant generatedAt,
            List<PublicationFlag> flags) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("snapshotId", snapshotId.toString());
        root.put("snapshotVersion", snapshotVersion);
        root.put("tenantKey", tenantKey);
        root.put("projectKey", projectKey);
        root.put("environmentKey", environmentKey);
        root.put("generatedAt", generatedAt.toString());
        ArrayNode flagNodes = root.putArray("flags");
        flags.forEach(flag -> appendFlag(flagNodes, flag));

        String checksum = checksum(root);
        root.put("checksum", checksum);
        return new CompiledSnapshot(root, checksum);
    }

    private void appendFlag(ArrayNode destination, PublicationFlag flag) {
        ObjectNode node = destination.addObject();
        node.put("flagKey", flag.flagKey());
        node.put("revisionNumber", flag.revisionNumber());
        node.put("valueType", flag.valueType().name());
        node.put("enabled", flag.enabled());
        node.put("defaultVariantKey", flag.defaultVariantKey());
        node.put("rolloutSeed", flag.rolloutSeed());

        ArrayNode variants = node.putArray("variants");
        for (PublishedVariant variant : flag.variants()) {
            ObjectNode variantNode = variants.addObject();
            variantNode.put("key", variant.key());
            variantNode.set("value", variant.value());
        }

        ArrayNode rules = node.putArray("rules");
        for (PublishedRule rule : flag.rules()) {
            ObjectNode ruleNode = rules.addObject();
            ruleNode.put("priority", rule.priority());
            ArrayNode conditions = ruleNode.putArray("conditions");
            for (PublishedCondition condition : rule.conditions()) {
                ObjectNode conditionNode = conditions.addObject();
                conditionNode.put("attribute", condition.attribute());
                conditionNode.put("operator", condition.operator());
                if (condition.operand() != null) {
                    conditionNode.set("operand", condition.operand());
                }
            }
            ObjectNode result = ruleNode.putObject("result");
            if (rule.resultVariantKey() != null) {
                result.put("variantKey", rule.resultVariantKey());
            } else {
                ArrayNode allocations = result.putArray("allocations");
                for (PublishedAllocation allocation : rule.allocations()) {
                    allocations.addObject()
                            .put("variantKey", allocation.variantKey())
                            .put("basisPoints", allocation.basisPoints());
                }
            }
        }
    }

    private String checksum(JsonNode payloadWithoutChecksum) {
        try {
            String json = objectMapper.writeValueAsString(payloadWithoutChecksum);
            String canonical = new JsonCanonicalizer(json).getEncodedString();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("snapshot cannot be serialized", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("snapshot cannot be canonicalized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public record CompiledSnapshot(JsonNode payload, String checksum) {
    }
}
