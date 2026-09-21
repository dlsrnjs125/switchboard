package io.github.dlsrnjs125.switchboard.controlplane.application;

import io.github.dlsrnjs125.switchboard.controlplane.application.SnapshotCompiler.CompiledSnapshot;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublicationFlag;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedAllocation;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedCondition;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedRule;
import io.github.dlsrnjs125.switchboard.controlplane.persistence.ControlPlaneRepository.PublishedVariant;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SnapshotCompilationBenchmark {
    @Benchmark
    public CompiledSnapshot compile(BenchmarkState state) {
        return state.compiler.compile(
                state.snapshotId, 1, "benchmark", "checkout", "production",
                state.generatedAt, state.flags);
    }

    @Benchmark
    public void validate(BenchmarkState state) {
        state.validator.validate(state.compiled.payload());
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"1", "100", "1000"})
        int flagCount;

        SnapshotCompiler compiler;
        SnapshotValidator validator;
        UUID snapshotId;
        Instant generatedAt;
        List<PublicationFlag> flags;
        CompiledSnapshot compiled;

        @Setup(Level.Trial)
        public void setUp() {
            compiler = new SnapshotCompiler(new ObjectMapper());
            validator = new SnapshotValidator();
            snapshotId = UUID.nameUUIDFromBytes(
                    ("phase09-publish-" + flagCount).getBytes(StandardCharsets.UTF_8));
            generatedAt = Instant.parse("2026-09-21T00:00:00Z");
            flags = flags(flagCount);
            compiled = compiler.compile(
                    snapshotId, 1, "benchmark", "checkout", "production", generatedAt, flags);
            validator.validate(compiled.payload());
        }

        private List<PublicationFlag> flags(int count) {
            List<PublicationFlag> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String key = "flag-" + index;
                result.add(new PublicationFlag(
                        UUID.nameUUIDFromBytes((key + "-flag").getBytes(StandardCharsets.UTF_8)),
                        UUID.nameUUIDFromBytes((key + "-revision").getBytes(StandardCharsets.UTF_8)),
                        key,
                        1,
                        ValueType.BOOLEAN,
                        true,
                        "off",
                        "seed-" + index,
                        List.of(
                                new PublishedVariant("off", JsonNodeFactory.instance.booleanNode(false)),
                                new PublishedVariant("on", JsonNodeFactory.instance.booleanNode(true))),
                        List.of(new PublishedRule(
                                0,
                                RuleResultType.ROLLOUT,
                                null,
                                List.of(new PublishedCondition(
                                        "plan", "EQUALS", JsonNodeFactory.instance.textNode("premium"))),
                                List.of(
                                        new PublishedAllocation("off", 5_000),
                                        new PublishedAllocation("on", 5_000))))));
            }
            return List.copyOf(result);
        }
    }
}
