package io.github.dlsrnjs125.switchboard.distribution.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;

class KafkaNotificationE2ETest extends DistributionPostgresSupport {
    private static final String TOPIC = "switchboard.snapshot-published.v1.test";
    private static final EmbeddedKafkaKraftBroker KAFKA = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);

    @BeforeAll
    static void startKafka() {
        KAFKA.afterPropertiesSet();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.destroy();
    }

    @Test
    void kafkaNotificationReconcilesPostgresSnapshotIntoCache() throws Exception {
        SnapshotFixture snapshot = insertSnapshot(4);
        SnapshotCache cache = new SnapshotCache();
        CountDownLatch applied = new CountDownLatch(1);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(ignored -> applied.countDown()));
        SnapshotNotificationConsumer consumer = new SnapshotNotificationConsumer(objectMapper, coordinator);

        var consumerProps = KafkaTestUtils.consumerProps(KAFKA, "distribution-e2e", false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new DefaultKafkaConsumerFactory<String, String>(
                consumerProps, new StringDeserializer(), new StringDeserializer());
        var container = new KafkaMessageListenerContainer<String, String>(
                factory, new ContainerProperties(TOPIC));
        container.setupMessageListener((MessageListener<String, String>) record -> consumer.consume(record.value()));
        container.start();
        ContainerTestUtils.waitForAssignment(container, 1);

        var producerProps = KafkaTestUtils.producerProps(KAFKA);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>(TOPIC, UUID.randomUUID().toString(), event(snapshot))).get();
            assertTrue(applied.await(10, TimeUnit.SECONDS));
        } finally {
            container.stop();
        }

        assertEquals(4, cache.get(scope()).orElseThrow().snapshotVersion());
        assertEquals(snapshot.checksum(), cache.get(scope()).orElseThrow().checksum());
    }

    private String event(SnapshotFixture snapshot) {
        return objectMapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "SNAPSHOT_PUBLISHED")
                .put("eventVersion", 1)
                .put("tenantKey", "acme")
                .put("projectKey", "checkout")
                .put("environmentKey", "production")
                .put("snapshotId", snapshot.id().toString())
                .put("snapshotVersion", snapshot.version())
                .put("checksum", snapshot.checksum())
                .put("occurredAt", clock.instant().toString())
                .toString();
    }
}
