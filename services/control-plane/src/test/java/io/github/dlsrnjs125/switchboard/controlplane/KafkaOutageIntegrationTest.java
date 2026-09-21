package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateEnvironment;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateProject;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.publication.KafkaSnapshotEventPublisher;
import io.github.dlsrnjs125.switchboard.controlplane.publication.OutboxRelay;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.node.JsonNodeFactory;

class KafkaOutageIntegrationTest extends PostgresIntegrationSupport {
    private static final String TOPIC = "switchboard.snapshot-published.v1.failure";
    private static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.3.1"));

    @BeforeAll
    static void startKafka() throws Exception {
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void committedOutboxSurvivesKafkaPauseAndPublishesAfterBrokerRecovery() throws Exception {
        createCommittedOutbox();
        UUID eventId = jdbc.queryForObject("SELECT id FROM outbox_events", UUID.class);
        Map<String, Object> producerProperties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.RETRIES_CONFIG, 0);
        DefaultKafkaProducerFactory<String, String> factory =
                new DefaultKafkaProducerFactory<>(producerProperties);
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(factory);
        OutboxRelay relay = new OutboxRelay(
                repository,
                new KafkaSnapshotEventPublisher(kafka, TOPIC, Duration.ofSeconds(3)),
                clock,
                transaction.getTransactionManager());

        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            relay.relay();
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        jdbc.update("UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), eventId);

        relay.relay();

        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM outbox_events", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM outbox_events", Object.class));
        assertEquals(eventId.toString(), consumeEventKey());
        factory.destroy();
    }

    private String consumeEventKey() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "phase-6-outbox-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) {
                    return records.iterator().next().key();
                }
            }
        }
        throw new AssertionError("recovered Kafka event was not observed");
    }

    private void createCommittedOutbox() {
        inTransaction(status -> service.createTenant("acme", "Acme", "alice"));
        inTransaction(status -> service.createProject(
                "acme", "alice", new CreateProject("checkout", "Checkout")));
        inTransaction(status -> service.createEnvironment(
                "acme", "checkout", "alice",
                new CreateEnvironment("prod", "Production", EnvironmentType.PRODUCTION)));
        inTransaction(status -> service.createFlag(
                "acme", "checkout", "alice", new CreateFlag("feature-a", ValueType.BOOLEAN)));
        var revision = inTransaction(status -> service.createDraftRevision(
                "acme", "checkout", "feature-a", "alice",
                new CreateRevision(
                        List.of(
                                new Variant("off", JsonNodeFactory.instance.booleanNode(false)),
                                new Variant("on", JsonNodeFactory.instance.booleanNode(true))),
                        "off", "feature-a-seed", List.of())));
        inTransaction(status -> service.publish(
                "acme", "checkout", "prod", "alice",
                new Publish("feature-a", revision.revisionNumber(), true, 0, UUID.randomUUID())));
        assertTrue(jdbc.queryForObject("SELECT count(*) = 1 FROM outbox_events", Boolean.class));
    }
}
