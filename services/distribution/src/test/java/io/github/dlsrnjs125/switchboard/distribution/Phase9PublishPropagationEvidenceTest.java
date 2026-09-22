package io.github.dlsrnjs125.switchboard.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.ImmutableContext;
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
import io.github.dlsrnjs125.switchboard.distribution.observability.DistributionTelemetry;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderState;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

@Tag("phase9")
@Tag("phase9-propagation")
class Phase9PublishPropagationEvidenceTest extends DistributionPostgresSupport {
    private static final String TOPIC = "switchboard.snapshot-published.v1.phase9-propagation";
    private static final EmbeddedKafkaKraftBroker KAFKA = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
    private static final int WARMUP_ITERATIONS = 5;
    private static final int MEASUREMENT_ITERATIONS = 30;
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @TempDir
    Path temporaryDirectory;

    private KafkaMessageListenerContainer<String, String> container;
    private GrpcServerLifecycle grpcServer;
    private SwitchboardProvider provider;

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
        if (provider != null) provider.shutdown();
        if (container != null) container.stop();
        if (grpcServer != null) grpcServer.stop();
    }

    @Test
    void recordsCommitBrokerDistributionSdkApplyAndAckPercentiles() throws Exception {
        Map<Long, Long> brokerAckNanos = new ConcurrentHashMap<>();
        Map<Long, Long> distributionApplyNanos = new ConcurrentHashMap<>();
        SimpleMeterRegistry distributionMeters = new SimpleMeterRegistry();
        DistributionTelemetry distributionTelemetry =
                new DistributionTelemetry(distributionMeters, ObservationRegistry.NOOP);
        SnapshotCache cache = new SnapshotCache();
        SessionRegistry sessions = new SessionRegistry(
                repository, cache, clock, 100, distributionTelemetry);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository,
                new DistributionSnapshotValidator(objectMapper),
                cache,
                new ProcessedEventWindow(),
                List.of(sessions),
                distributionTelemetry);
        startConsumer(new SnapshotNotificationConsumer(objectMapper, coordinator), distributionApplyNanos);
        startGrpc(coordinator, sessions, distributionTelemetry);

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

        var producerProps = KafkaTestUtils.producerProps(KAFKA);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            OutboxRelay relay = new OutboxRelay(
                    controlRepository,
                    (eventId, payload) -> {
                        long version = payload.path("snapshotVersion").asLong();
                        producer.send(new ProducerRecord<>(
                                TOPIC, eventId.toString(), payload.toString())).get();
                        brokerAckNanos.put(version, System.nanoTime());
                    },
                    clock,
                    transactions.getTransactionManager());

            long version = publishRevision(control, transactions, 0, false);
            relay.relay();
            long initialVersion = version;
            await(() -> distributionApplyNanos.containsKey(initialVersion), "initial Distribution apply");
            startProvider();
            await(() -> provider.switchboardState() == SwitchboardProviderState.READY,
                    "initial SDK READY");
            await(() -> provider.lastAppliedVersion() == initialVersion, "initial SDK version");
            Timer acknowledgementTimer = awaitAckTimer(distributionMeters);
            await(() -> acknowledgementTimer.count() >= 1, "initial SDK ACK");

            for (int warmup = 0; warmup < WARMUP_ITERATIONS; warmup++) {
                long previousAckCount = acknowledgementTimer.count();
                version = publishRevision(control, transactions, version, warmup % 2 == 0);
                relay.relay();
                long expected = version;
                await(() -> provider.lastAppliedVersion() == expected, "warm-up SDK apply");
                await(() -> acknowledgementTimer.count() > previousAckCount, "warm-up SDK ACK");
            }

            long[] publishCommit = new long[MEASUREMENT_ITERATIONS];
            long[] commitToBrokerAck = new long[MEASUREMENT_ITERATIONS];
            long[] brokerAckToDistributionApply = new long[MEASUREMENT_ITERATIONS];
            long[] distributionApplyToSdkApply = new long[MEASUREMENT_ITERATIONS];
            long[] sdkApplyToAck = new long[MEASUREMENT_ITERATIONS];
            long[] commitToSdkAck = new long[MEASUREMENT_ITERATIONS];
            long[] publishStartToSdkAck = new long[MEASUREMENT_ITERATIONS];

            for (int sample = 0; sample < MEASUREMENT_ITERATIONS; sample++) {
                boolean defaultOn = sample % 2 == 0;
                long previousAckCount = acknowledgementTimer.count();
                long publishStarted = System.nanoTime();
                version = publishRevision(control, transactions, version, defaultOn);
                long committed = System.nanoTime();
                long expected = version;
                relay.relay();
                await(() -> brokerAckNanos.containsKey(expected), "broker ACK");
                await(() -> distributionApplyNanos.containsKey(expected), "Distribution apply");
                await(() -> provider.lastAppliedVersion() == expected, "SDK apply");
                long sdkAppliedObserved = System.nanoTime();
                await(() -> acknowledgementTimer.count() > previousAckCount, "SDK ACK");
                long sdkAckObserved = System.nanoTime();

                long brokerAcknowledged = brokerAckNanos.get(expected);
                long distributionApplied = distributionApplyNanos.get(expected);
                assertTrue(brokerAcknowledged >= committed);
                assertTrue(distributionApplied >= brokerAcknowledged);
                publishCommit[sample] = committed - publishStarted;
                commitToBrokerAck[sample] = brokerAcknowledged - committed;
                brokerAckToDistributionApply[sample] = distributionApplied - brokerAcknowledged;
                distributionApplyToSdkApply[sample] = sdkAppliedObserved - distributionApplied;
                sdkApplyToAck[sample] = sdkAckObserved - sdkAppliedObserved;
                commitToSdkAck[sample] = sdkAckObserved - committed;
                publishStartToSdkAck[sample] = sdkAckObserved - publishStarted;
            }

            assertEquals(1 + WARMUP_ITERATIONS + MEASUREMENT_ITERATIONS, provider.lastAppliedVersion());
            assertEquals(false, provider.getBooleanEvaluation(
                    "checkout-v2", true, ImmutableContext.EMPTY).getValue());
            writeEvidence(Map.of(
                    "publishCommitLatencyMicros", percentiles(publishCommit),
                    "commitToBrokerAckLatencyMicros", percentiles(commitToBrokerAck),
                    "brokerAckToDistributionApplyLatencyMicros", percentiles(brokerAckToDistributionApply),
                    "distributionApplyToSdkApplyObservedLatencyMicros",
                    percentiles(distributionApplyToSdkApply),
                    "sdkApplyObservedToServerAckObservedLatencyMicros", percentiles(sdkApplyToAck),
                    "commitToSdkAckObservedLatencyMicros", percentiles(commitToSdkAck),
                    "publishStartToSdkAckObservedLatencyMicros", percentiles(publishStartToSdkAck)));
        }
    }

    private long publishRevision(
            ControlPlaneService control,
            TransactionTemplate transactions,
            long expectedVersion,
            boolean defaultOn) {
        var revision = transactions.execute(status -> control.createDraftRevision(
                "acme",
                "checkout",
                "checkout-v2",
                "alice",
                new CreateRevision(
                        List.of(
                                new Variant("off", JsonNodeFactory.instance.booleanNode(false)),
                                new Variant("on", JsonNodeFactory.instance.booleanNode(true))),
                        defaultOn ? "on" : "off",
                        "checkout-seed",
                        List.of())));
        assertNotNull(revision);
        var published = transactions.execute(status -> control.publish(
                "acme",
                "checkout",
                "production",
                "alice",
                new Publish(
                        "checkout-v2",
                        revision.revisionNumber(),
                        true,
                        expectedVersion,
                        UUID.randomUUID())));
        assertNotNull(published);
        return published.snapshotVersion();
    }

    private void startConsumer(
            SnapshotNotificationConsumer consumer,
            Map<Long, Long> distributionApplyNanos) {
        var consumerProps = KafkaTestUtils.consumerProps(
                KAFKA, "phase9-propagation-" + UUID.randomUUID(), false);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new DefaultKafkaConsumerFactory<String, String>(
                consumerProps, new StringDeserializer(), new StringDeserializer());
        container = new KafkaMessageListenerContainer<>(factory, new ContainerProperties(TOPIC));
        container.setupMessageListener((MessageListener<String, String>) record -> {
            consumer.consume(record.value());
            try {
                long version = objectMapper.readTree(record.value()).path("snapshotVersion").asLong();
                distributionApplyNanos.put(version, System.nanoTime());
            } catch (Exception exception) {
                throw new IllegalStateException("cannot inspect Phase 9 notification", exception);
            }
        });
        container.start();
        ContainerTestUtils.waitForAssignment(container, 1);
    }

    private void startGrpc(
            SnapshotCoordinator coordinator,
            SessionRegistry sessions,
            DistributionTelemetry telemetry) {
        SnapshotDistributionGrpcService service =
                new SnapshotDistributionGrpcService(coordinator, sessions, clock, telemetry);
        grpcServer = new GrpcServerLifecycle(
                service, new CredentialServerInterceptor(repository), sessions, 0);
        grpcServer.start();
    }

    private void startProvider() {
        provider = new SwitchboardProvider(
                new SwitchboardProviderConfig(
                        "localhost:" + grpcServer.port(),
                        bearer(),
                        "orders",
                        "checkout",
                        "production",
                        temporaryDirectory.resolve("phase9-propagation-lkg.json"),
                        Duration.ofSeconds(30),
                        Duration.ofDays(7),
                        Duration.ofMillis(25),
                        Duration.ofSeconds(1),
                        0.2,
                        clock),
                new SimpleMeterRegistry(),
                ObservationRegistry.NOOP);
        provider.initialize(ImmutableContext.EMPTY);
    }

    private Timer awaitAckTimer(SimpleMeterRegistry meters) throws InterruptedException {
        final Timer[] timer = new Timer[1];
        await(() -> {
            timer[0] = meters.find("switchboard.distribution.snapshot.ack.latency")
                    .tag("outcome", "accepted")
                    .timer();
            return timer[0] != null;
        }, "Distribution ACK timer");
        return timer[0];
    }

    private void await(java.util.function.BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), () -> description + " deadline exceeded");
    }

    private Map<String, Long> percentiles(long[] nanos) {
        long[] sorted = Arrays.stream(nanos).filter(value -> value >= 0).sorted().toArray();
        assertEquals(nanos.length, sorted.length);
        return Map.of(
                "p50", micros(sorted, 0.50),
                "p95", micros(sorted, 0.95),
                "p99", micros(sorted, 0.99),
                "max", TimeUnit.NANOSECONDS.toMicros(sorted[sorted.length - 1]));
    }

    private long micros(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return TimeUnit.NANOSECONDS.toMicros(sorted[index]);
    }

    private void writeEvidence(Map<String, Map<String, Long>> percentiles) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-PRP-001");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("kafka", "Spring EmbeddedKafkaKraftBroker");
        result.put("kafkaVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("clients", 1);
        result.put("warmupIterations", WARMUP_ITERATIONS);
        result.put("measurementIterations", MEASUREMENT_ITERATIONS);
        result.put("observationResolutionMillis", 1);
        result.put("ackBoundary", "Distribution server accepted SDK ACK after provider atomic apply and LKG write");
        result.put("percentiles", percentiles);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.propagation.result",
                "build/reports/phase-09/publish-propagation.json"));
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }
}
