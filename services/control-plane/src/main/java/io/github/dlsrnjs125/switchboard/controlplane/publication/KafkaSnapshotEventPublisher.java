package io.github.dlsrnjs125.switchboard.controlplane.publication;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "switchboard.outbox.relay.enabled", havingValue = "true")
public class KafkaSnapshotEventPublisher implements SnapshotEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaSnapshotEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${switchboard.outbox.topic:switchboard.snapshot-published.v1}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(UUID eventId, JsonNode payload) throws Exception {
        kafka.send(topic, eventId.toString(), payload.toString()).get();
    }
}
