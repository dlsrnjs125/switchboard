package io.github.dlsrnjs125.switchboard.evaluation;

import static io.github.dlsrnjs125.switchboard.evaluation.FlagValue.booleanValue;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import io.github.dlsrnjs125.switchboard.evaluation.RuleResult.Allocation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EvaluationBenchmark {
    @Benchmark
    public EvaluationResult staticDefault(BenchmarkState state) {
        return state.engine.evaluate(state.staticFlag, ValueType.BOOLEAN, state.context);
    }

    @Benchmark
    public EvaluationResult targeting(BenchmarkState state) {
        return state.engine.evaluate(state.targetingFlag, ValueType.BOOLEAN, state.context);
    }

    @Benchmark
    public EvaluationResult percentageRollout(BenchmarkState state) {
        return state.engine.evaluate(state.rolloutFlag, ValueType.BOOLEAN, state.context);
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        EvaluationEngine engine;
        EvaluationContext context;
        FlagDefinition staticFlag;
        FlagDefinition targetingFlag;
        FlagDefinition rolloutFlag;

        @Setup(Level.Trial)
        public void setUp() {
            engine = new EvaluationEngine();
            context = new EvaluationContext("alice", Map.of("plan", "premium", "country", "KR"));
            staticFlag = flag(List.of());
            targetingFlag = flag(List.of(new TargetingRule(
                    0,
                    List.of(
                            new Condition("plan", ConditionOperator.EQUALS, "premium"),
                            new Condition("country", ConditionOperator.EQUALS, "KR")),
                    new RuleResult.Variant("on"))));
            rolloutFlag = flag(List.of(new TargetingRule(
                    0,
                    List.of(new Condition("plan", ConditionOperator.EQUALS, "premium")),
                    new RuleResult.Rollout(List.of(
                            new Allocation("off", 5_000),
                            new Allocation("on", 5_000))))));
        }

        private FlagDefinition flag(List<TargetingRule> rules) {
            return new FlagDefinition(
                    "checkout-v2",
                    ValueType.BOOLEAN,
                    true,
                    "off",
                    "checkout-seed",
                    List.of(new Variant("off", booleanValue(false)), new Variant("on", booleanValue(true))),
                    rules);
        }
    }
}
