package io.github.dlsrnjs125.switchboard.distribution.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlsrnjs125.switchboard.distribution.domain.DistributionTypes.SnapshotNotification;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SnapshotNotificationConsumer {
    private final ObjectMapper objectMapper;
    private final SnapshotCoordinator coordinator;

    public SnapshotNotificationConsumer(ObjectMapper objectMapper, SnapshotCoordinator coordinator) {
        this.objectMapper = objectMapper;
        this.coordinator = coordinator;
    }

    @KafkaListener(
            topics = "${switchboard.distribution.snapshot-topic}",
            groupId = "${switchboard.distribution.consumer-group}")
    public void consume(String payload) {
        coordinator.reconcile(parse(payload));
    }

    SnapshotNotification parse(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            if (!"SNAPSHOT_PUBLISHED".equals(event.path("eventType").asText())
                    || event.path("eventVersion").asInt() != 1) {
                throw new IllegalArgumentException("unsupported snapshot notification contract");
            }
            return new SnapshotNotification(
                    UUID.fromString(event.path("eventId").asText()),
                    required(event, "tenantKey"),
                    required(event, "projectKey"),
                    required(event, "environmentKey"),
                    UUID.fromString(event.path("snapshotId").asText()),
                    event.path("snapshotVersion").asLong(),
                    required(event, "checksum"));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid snapshot notification", exception);
        }
    }

    private String required(JsonNode event, String field) {
        String value = event.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }
}
