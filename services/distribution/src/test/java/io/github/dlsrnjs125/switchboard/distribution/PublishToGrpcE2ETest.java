package io.github.dlsrnjs125.switchboard.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateFlag;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.CreateRevision;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Publish;
import io.github.dlsrnjs125.switchboard.controlplane.application.Commands.Variant;
import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler;
import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotValidator;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.infrastructure.UuidV7Generator;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository;
import io.github.dlsrnjs125.switchboard.controlplane.publication.OutboxRelay;
import io.github.dlsrnjs125.switchboard.distribution.grpc.GrpcServerLifecycle;
import io.github.dlsrnjs125.switchboard.distribution.grpc.SessionRegistry;
import io.github.dlsrnjs125.switchboard.distribution.grpc.SnapshotDistributionGrpcService;
import io.github.dlsrnjs125.switchboard.distribution.messaging.SnapshotNotificationConsumer;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

class PublishToGrpcE2ETest extends DistributionPostgresSupport {
    private static final String TOPIC = "switchboard.snapshot-published.v1.publish-e2e";
    private static final EmbeddedKafkaKraftBroker KAFKA = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);

    private KafkaMessageListenerContainer<String, String> container;
    private GrpcServerLifecycle grpcServer;
    private ManagedChannel channel;

    @BeforeAll
    static void startKafka() {
        KAFKA.afterPropertiesSet();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.destroy();
    }

    @AfterEach
    void stopComponents() {
        if (container != null) container.stop();
        if (channel != null) channel.shutdownNow();
        if (grpcServer != null) grpcServer.stop();
    }

    @Test
    void controlPlanePublishFlowsThroughOutboxKafkaAndGrpcFullSnapshot() throws Exception {
        SnapshotCache cache = new SnapshotCache();
        SessionRegistry sessions = new SessionRegistry(repository, cache, clock);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions));
        startConsumer(new SnapshotNotificationConsumer(objectMapper, coordinator));
        startGrpc(coordinator, sessions);

        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<ServerMessage> received = new AtomicReference<>();
        authenticatedStub().subscribe(SubscribeRequest.newBuilder()
                .setClientApplicationKey("orders")
                .setProjectKey("checkout")
                .setEnvironmentKey("production")
                .setSupportedSchemaVersion(1)
                .build(), new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage value) {
                        if (value.hasFullSnapshot()) {
                            received.set(value);
                            delivered.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        tools.jackson.databind.ObjectMapper controlJson = new tools.jackson.databind.ObjectMapper();
        ControlPlaneRepository controlRepository = new ControlPlaneRepository(
                new NamedParameterJdbcTemplate(DATA_SOURCE), controlJson);
        ControlPlaneService control = new ControlPlaneService(
                controlRepository,
                new UuidV7Generator(),
                clock,
                new SnapshotCompiler(controlJson),
                new SnapshotValidator());
        transactions.execute(status -> control.createFlag(
                "acme", "checkout", "alice", new CreateFlag("checkout-v2", ValueType.BOOLEAN)));
        var revision = transactions.execute(status -> control.createDraftRevision(
                "acme", "checkout", "checkout-v2", "alice",
                new CreateRevision(
                        List.of(
                                new Variant("off", JsonNodeFactory.instance.booleanNode(false)),
                                new Variant("on", JsonNodeFactory.instance.booleanNode(true))),
                        "off", "checkout-seed", List.of())));
        var published = transactions.execute(status -> control.publish(
                "acme", "checkout", "production", "alice",
                new Publish("checkout-v2", revision.revisionNumber(), true, 0, UUID.randomUUID())));

        var producerProps = KafkaTestUtils.producerProps(KAFKA);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            OutboxRelay relay = new OutboxRelay(controlRepository, (eventId, payload) ->
                    producer.send(new ProducerRecord<>(TOPIC, eventId.toString(), payload.toString())).get(),
                    clock, transactions.getTransactionManager());
            relay.relay();
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
        }

        assertEquals(published.snapshotVersion(), received.get().getFullSnapshot().getSnapshotVersion());
        assertEquals(published.checksum(), received.get().getFullSnapshot().getChecksum());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL", Integer.class));
    }

    private void startConsumer(SnapshotNotificationConsumer consumer) {
        var consumerProps = KafkaTestUtils.consumerProps(KAFKA, "publish-to-grpc-e2e", false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new DefaultKafkaConsumerFactory<String, String>(
                consumerProps, new StringDeserializer(), new StringDeserializer());
        container = new KafkaMessageListenerContainer<>(factory, new ContainerProperties(TOPIC));
        container.setupMessageListener((MessageListener<String, String>) record -> consumer.consume(record.value()));
        container.start();
        ContainerTestUtils.waitForAssignment(container, 1);
    }

    private void startGrpc(SnapshotCoordinator coordinator, SessionRegistry sessions) {
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        grpcServer = new GrpcServerLifecycle(service, new CredentialServerInterceptor(repository), sessions, 0);
        grpcServer.start();
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.port()).usePlaintext().build();
    }

    private SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub authenticatedStub() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer());
        return SnapshotDistributionServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
}
