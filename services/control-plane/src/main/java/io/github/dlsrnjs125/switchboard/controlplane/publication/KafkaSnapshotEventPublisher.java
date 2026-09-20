package io.github.dlsrnjs125.switchboard.controlplane.publication;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    private final Duration publishTimeout;

    public KafkaSnapshotEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${switchboard.outbox.topic:switchboard.snapshot-published.v1}") String topic,
            @Value("${switchboard.outbox.publish-timeout:PT10S}") Duration publishTimeout) {
        this.kafka = kafka;
        this.topic = topic;
        if (publishTimeout.isZero() || publishTimeout.isNegative()) {
            throw new IllegalArgumentException("switchboard.outbox.publish-timeout must be positive");
        }
        this.publishTimeout = publishTimeout;
    }

    @Override
    public void publish(UUID eventId, JsonNode payload) throws Exception {
        kafka.send(topic, eventId.toString(), payload.toString())
                .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
