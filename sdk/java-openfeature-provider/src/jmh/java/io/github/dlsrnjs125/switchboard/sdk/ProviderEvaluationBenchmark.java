package io.github.dlsrnjs125.switchboard.sdk;

import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import io.github.dlsrnjs125.switchboard.contracts.distribution.v1.FullSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.erdtman.jcs.JsonCanonicalizer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProviderEvaluationBenchmark {
    @Benchmark
    public ProviderEvaluation<Boolean> staticDefault(BenchmarkState state) {
        return state.staticProvider.getBooleanEvaluation(state.key, false, state.context);
    }

    @Benchmark
    public ProviderEvaluation<Boolean> targeting(BenchmarkState state) {
        return state.targetingProvider.getBooleanEvaluation(state.key, false, state.context);
    }

    @Benchmark
    public ProviderEvaluation<Boolean> percentageSplit(BenchmarkState state) {
        return state.splitProvider.getBooleanEvaluation(state.key, false, state.context);
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"1", "100", "1000"})
        int flagCount;

        SwitchboardProvider staticProvider;
        SwitchboardProvider targetingProvider;
        SwitchboardProvider splitProvider;
        ImmutableContext context;
        String key;
        private Path directory;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            directory = Files.createTempDirectory("switchboard-phase09-jmh-");
            Clock clock = Clock.systemUTC();
            staticProvider = provider(clock, "static", RuleShape.STATIC);
            targetingProvider = provider(clock, "targeting", RuleShape.TARGETING);
            splitProvider = provider(clock, "split", RuleShape.SPLIT);
            key = "flag-" + (flagCount - 1);
            context = new ImmutableContext("benchmark-user", Map.of(
                    "plan", new dev.openfeature.sdk.Value("premium"),
                    "country", new dev.openfeature.sdk.Value("KR")));
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            staticProvider.shutdown();
            targetingProvider.shutdown();
            splitProvider.shutdown();
            Files.deleteIfExists(directory.resolve("static-lkg.json"));
            Files.deleteIfExists(directory.resolve("targeting-lkg.json"));
            Files.deleteIfExists(directory.resolve("split-lkg.json"));
            Files.deleteIfExists(directory);
        }

        private SwitchboardProvider provider(Clock clock, String name, RuleShape shape) throws Exception {
            SwitchboardProviderConfig config = new SwitchboardProviderConfig(
                    "unused:1", "benchmark-credential", "benchmark-client", "benchmark-project",
                    "benchmark", directory.resolve(name + "-lkg.json"), Duration.ofMinutes(5),
                    Duration.ofDays(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 0, clock);
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            SwitchboardProvider result = new SwitchboardProvider(
                    config, new SnapshotDecoder(), new DiskLkgStore(config.lkgPath()),
                    new NoopTransport(), scheduler);
            result.initialize(ImmutableContext.EMPTY);
            result.onSnapshot(snapshot(flagCount, shape));
            return result;
        }

        private FullSnapshot snapshot(int count, RuleShape shape) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            UUID snapshotId = UUID.nameUUIDFromBytes(
                    ("phase09-" + shape + "-" + count).getBytes(StandardCharsets.UTF_8));
            root.put("schemaVersion", 1);
            root.put("snapshotId", snapshotId.toString());
            root.put("snapshotVersion", 1);
            root.put("tenantKey", "benchmark");
            root.put("projectKey", "benchmark-project");
            root.put("environmentKey", "benchmark");
            root.put("generatedAt", Instant.parse("2026-09-21T00:00:00Z").toString());
            var flags = root.putArray("flags");
            for (int index = 0; index < count; index++) {
                appendFlag(flags.addObject(), index, shape);
            }
            String unsigned = new JsonCanonicalizer(mapper.writeValueAsString(root)).getEncodedString();
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(unsigned.getBytes(StandardCharsets.UTF_8)));
            root.put("checksum", checksum);
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsString(root))
                    .getEncodedString().getBytes(StandardCharsets.UTF_8);
            return FullSnapshot.newBuilder()
                    .setSnapshotVersion(1)
                    .setSchemaVersion(1)
                    .setChecksum(checksum)
                    .setCanonicalJson(com.google.protobuf.ByteString.copyFrom(canonical))
                    .setDeliveryId(UUID.randomUUID().toString())
                    .build();
        }

        private void appendFlag(ObjectNode flag, int index, RuleShape shape) {
            flag.put("flagKey", "flag-" + index);
            flag.put("revisionNumber", 1);
            flag.put("valueType", "BOOLEAN");
            flag.put("enabled", true);
            flag.put("defaultVariantKey", "off");
            flag.put("rolloutSeed", "seed-" + index);
            var variants = flag.putArray("variants");
            variants.addObject().put("key", "off").put("value", false);
            variants.addObject().put("key", "on").put("value", true);
            var rules = flag.putArray("rules");
            if (shape == RuleShape.TARGETING) {
                var rule = rules.addObject();
                rule.put("priority", 0);
                var conditions = rule.putArray("conditions");
                conditions.addObject().put("attribute", "plan").put("operator", "EQUALS")
                        .put("operand", "premium");
                rule.putObject("result").put("variantKey", "on");
            }
            if (shape == RuleShape.SPLIT) {
                var rule = rules.addObject();
                rule.put("priority", rules.size() - 1);
                var conditions = rule.putArray("conditions");
                conditions.addObject().put("attribute", "country").put("operator", "EQUALS")
                        .put("operand", "KR");
                var allocations = rule.putObject("result").putArray("allocations");
                allocations.addObject().put("variantKey", "off").put("basisPoints", 5000);
                allocations.addObject().put("variantKey", "on").put("basisPoints", 5000);
            }
        }

        private enum RuleShape { STATIC, TARGETING, SPLIT }
    }

    private static final class NoopTransport implements SnapshotTransport {
        @Override public void start(LongSupplier lastAppliedVersion, Listener listener) { listener.onConnected(); }
        @Override public void acknowledge(String deliveryId, long snapshotVersion, String checksum) { }
        @Override public void reject(long snapshotVersion, String reasonCode, String detail) { }
        @Override public void requestResync(long lastAppliedVersion, String reasonCode) { }
        @Override public void close() { }
    }
}
