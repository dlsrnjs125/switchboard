package io.github.dlsrnjs125.switchboard.controlplane.publication;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

@FunctionalInterface
public interface SnapshotEventPublisher {
    void publish(UUID eventId, JsonNode payload) throws Exception;
}
