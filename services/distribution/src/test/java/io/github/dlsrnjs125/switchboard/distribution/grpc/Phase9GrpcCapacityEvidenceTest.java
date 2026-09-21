package io.github.dlsrnjs125.switchboard.distribution.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.OperatingSystemMXBean;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckRequest;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.AckResponse;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.ServerMessage;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SnapshotDistributionServiceGrpc;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.SubscribeRequest;
import io.github.dlsrnjs125.switchboard.distribution.DistributionPostgresSupport;
import io.github.dlsrnjs125.switchboard.distribution.security.CredentialServerInterceptor;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.DistributionSnapshotValidator;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.ProcessedEventWindow;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCache;
import io.github.dlsrnjs125.switchboard.distribution.snapshot.SnapshotCoordinator;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("phase9")
class Phase9GrpcCapacityEvidenceTest extends DistributionPostgresSupport {
    private static final int[] CLIENT_COUNTS = {100, 500, 1_000};
    private static final int CONNECTION_BATCH_SIZE = 10;
    private static final Duration DEADLINE = Duration.ofMinutes(3);

    @Test
    void recordsConnectionBroadcastAndAckDistributions() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        long version = 1;
        for (int clients : CLIENT_COUNTS) {
            scenarios.add(runScenario(clients, version));
            version += 2;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("evidenceId", "EV-P09-BASELINE-001");
        result.put("capturedAt", Instant.now().toString());
        result.put("jdk", System.getProperty("java.runtime.version"));
        result.put("vm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("postgresImage", "postgres:18.6-alpine");
        result.put("connectionBatchSize", CONNECTION_BATCH_SIZE);
        result.put("ackConcurrency", 4);
        result.put("scenarios", scenarios);

        Path output = Path.of(System.getProperty(
                "switchboard.phase9.result", "build/reports/phase-09/grpc-capacity.json"));
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private Map<String, Object> runScenario(int clients, long initialVersion) throws Exception {
        insertSnapshot(initialVersion);
        SnapshotCache cache = new SnapshotCache();
        SessionRegistry sessions = new SessionRegistry(repository, cache, clock, clients + 10);
        SnapshotCoordinator coordinator = new SnapshotCoordinator(
                repository, new DistributionSnapshotValidator(objectMapper), cache,
                new ProcessedEventWindow(), List.of(sessions));
        SnapshotDistributionGrpcService service = new SnapshotDistributionGrpcService(coordinator, sessions, clock);
        GrpcServerLifecycle server = new GrpcServerLifecycle(
                service, new CredentialServerInterceptor(repository), sessions, 0);
        server.start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.port())
                .usePlaintext().build();
        try {
            SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub stub = authenticated(channel);
            SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub blocking =
                    authenticatedBlocking(channel);
            CountDownLatch connected = new CountDownLatch(clients);
            CountDownLatch[] clientConnected = new CountDownLatch[clients];
            CountDownLatch delivered = new CountDownLatch(clients);
            CountDownLatch acknowledged = new CountDownLatch(clients);
            AtomicLongArray started = new AtomicLongArray(clients);
            long[] connectNanos = new long[clients];
            long[] broadcastNanos = new long[clients];
            long[] ackNanos = new long[clients];
            ServerMessage[] deliveredMessages = new ServerMessage[clients];
            List<String> errors = new CopyOnWriteArrayList<>();
            long heapBefore = usedHeapAfterGc();
            long cpuBefore = processCpuNanos();

            for (int index = 0; index < clients; index++) {
                int clientIndex = index;
                clientConnected[clientIndex] = new CountDownLatch(1);
                started.set(clientIndex, System.nanoTime());
                stub.subscribe(subscribe(), new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                        if (!message.hasFullSnapshot()) return;
                        long now = System.nanoTime();
                        long snapshotVersion = message.getFullSnapshot().getSnapshotVersion();
                        if (snapshotVersion == initialVersion) {
                            connectNanos[clientIndex] = now - started.get(clientIndex);
                            connected.countDown();
                            clientConnected[clientIndex].countDown();
                            return;
                        }
                        if (snapshotVersion == initialVersion + 1) {
                            broadcastNanos[clientIndex] = now - broadcastStarted[0];
                            deliveredMessages[clientIndex] = message;
                            delivered.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        errors.add("STREAM_ERROR:" + Status.fromThrowable(throwable).getCode());
                        connected.countDown();
                        clientConnected[clientIndex].countDown();
                        delivered.countDown();
                        acknowledged.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        // Streams are expected to stay open until scenario cleanup.
                    }
                });
                if ((index + 1) % CONNECTION_BATCH_SIZE == 0 || index + 1 == clients) {
                    int batchStart = index - (index % CONNECTION_BATCH_SIZE);
                    for (int admitted = batchStart; admitted <= index; admitted++) {
                        assertTrue(clientConnected[admitted].await(
                                DEADLINE.toSeconds(), TimeUnit.SECONDS), "connection batch deadline exceeded");
                    }
                    assertTrue(errors.isEmpty(), () -> "gRPC connection errors: " + errors);
                }
            }
            assertTrue(connected.await(DEADLINE.toSeconds(), TimeUnit.SECONDS), "connection deadline exceeded");
            assertEquals(clients, sessions.size());

            insertSnapshot(initialVersion + 1);
            broadcastStarted[0] = System.nanoTime();
            coordinator.current(scope()).orElseThrow();
            assertTrue(delivered.await(DEADLINE.toSeconds(), TimeUnit.SECONDS), "broadcast deadline exceeded");
            ExecutorService ackWorkers = Executors.newFixedThreadPool(4);
            for (int index = 0; index < clients; index++) {
                int clientIndex = index;
                ackWorkers.submit(() -> {
                    try {
                        ServerMessage message = deliveredMessages[clientIndex];
                        AckResponse response = blocking.acknowledge(AckRequest.newBuilder()
                                .setClientApplicationKey("orders")
                                .setEnvironmentKey("production")
                                .setSnapshotVersion(message.getFullSnapshot().getSnapshotVersion())
                                .setChecksum(message.getFullSnapshot().getChecksum())
                                .setDeliveryId(message.getFullSnapshot().getDeliveryId())
                                .build());
                        if (!response.getAccepted()) errors.add("ACK_REJECTED:" + clientIndex);
                        ackNanos[clientIndex] = System.nanoTime() - broadcastStarted[0];
                    } catch (RuntimeException exception) {
                        errors.add("ACK_ERROR:" + Status.fromThrowable(exception).getCode());
                    } finally {
                        acknowledged.countDown();
                    }
                });
            }
            assertTrue(acknowledged.await(DEADLINE.toSeconds(), TimeUnit.SECONDS), "ACK deadline exceeded");
            ackWorkers.shutdownNow();
            assertTrue(errors.isEmpty(), () -> "gRPC workload errors: " + errors);

            long elapsedCpu = processCpuNanos() - cpuBefore;
            long heapAfter = usedHeapAfterGc();
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("clients", clients);
            scenario.put("connectedSessions", sessions.size());
            scenario.put("connectionLatencyMicros", percentiles(connectNanos));
            scenario.put("broadcastLatencyMicros", percentiles(broadcastNanos));
            scenario.put("broadcastToAckLatencyMicros", percentiles(ackNanos));
            scenario.put("heapDeltaAfterGcBytes", heapAfter - heapBefore);
            scenario.put("processCpuMillis", TimeUnit.NANOSECONDS.toMillis(elapsedCpu));
            scenario.put("errors", errors.size());
            return scenario;
        } finally {
            channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
            server.stop();
        }
    }

    private final long[] broadcastStarted = new long[1];

    private SnapshotDistributionServiceGrpc.SnapshotDistributionServiceStub authenticated(ManagedChannel channel) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer());
        return SnapshotDistributionServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private SnapshotDistributionServiceGrpc.SnapshotDistributionServiceBlockingStub authenticatedBlocking(
            ManagedChannel channel) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + bearer());
        return SnapshotDistributionServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private SubscribeRequest subscribe() {
        return SubscribeRequest.newBuilder()
                .setClientApplicationKey("orders")
                .setProjectKey("checkout")
                .setEnvironmentKey("production")
                .setSupportedSchemaVersion(1)
                .build();
    }

    private Map<String, Long> percentiles(long[] nanos) {
        long[] sorted = Arrays.stream(nanos).filter(value -> value > 0).sorted().toArray();
        assertEquals(nanos.length, sorted.length, "every client must produce one timing sample");
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

    private long usedHeapAfterGc() throws InterruptedException {
        System.gc();
        Thread.sleep(250);
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private long processCpuNanos() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean) {
            return bean.getProcessCpuTime();
        }
        return 0;
    }
}
