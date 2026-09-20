package io.github.dlsrnjs125.switchboard.evaluation;

import static io.github.dlsrnjs125.switchboard.evaluation.FlagValue.booleanValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import io.github.dlsrnjs125.switchboard.evaluation.RuleResult.Allocation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationEngineTest {
    private final EvaluationEngine engine = new EvaluationEngine();

    @Test
    void returnsDefaultForDisabledFlagWithoutEvaluatingRules() {
        FlagDefinition flag = booleanFlag(
                false,
                List.of(new TargetingRule(
                        0,
                        List.of(new Condition("age", ConditionOperator.GREATER_THAN, 18)),
                        new RuleResult.Variant("on"))));

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext(null, Map.of("age", "invalid")));

        assertEquals(EvaluationReason.DISABLED, result.reason());
        assertEquals("off", result.variantKey().orElseThrow());
        assertFalse(result.hasError());
    }

    @Test
    void evaluatesRulesByAscendingPriorityAndCombinesConditionsWithAnd() {
        FlagDefinition flag = booleanFlag(
                true,
                List.of(
                        new TargetingRule(
                                20,
                                List.of(new Condition("plan", ConditionOperator.EQUALS, "premium")),
                                new RuleResult.Variant("off")),
                        new TargetingRule(
                                10,
                                List.of(
                                        new Condition("plan", ConditionOperator.EQUALS, "premium"),
                                        new Condition("country", ConditionOperator.EQUALS, "KR")),
                                new RuleResult.Variant("on"))));

        EvaluationResult result = engine.evaluate(
                flag,
                ValueType.BOOLEAN,
                new EvaluationContext("alice", Map.of("plan", "premium", "country", "KR")));

        assertEquals(EvaluationReason.TARGETING_MATCH, result.reason());
        assertEquals("on", result.variantKey().orElseThrow());
        assertEquals(10, result.metadata().get("rulePriority"));
    }

    @Test
    void returnsDefaultWhenNoRuleMatches() {
        FlagDefinition flag = booleanFlag(
                true,
                List.of(new TargetingRule(
                        0,
                        List.of(new Condition("plan", ConditionOperator.EQUALS, "premium")),
                        new RuleResult.Variant("on"))));

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext("alice", Map.of("plan", "free")));

        assertEquals(EvaluationReason.DEFAULT, result.reason());
        assertEquals("off", result.variantKey().orElseThrow());
    }

    @Test
    void rolloutUsesHalfOpenAllocationBoundaries() {
        FlagDefinition flag = booleanFlag(
                true,
                List.of(new TargetingRule(
                        0,
                        List.of(new Condition("eligible", ConditionOperator.EQUALS, true)),
                        new RuleResult.Rollout(List.of(
                                new Allocation("off", 5_000),
                                new Allocation("on", 5_000))))));
        String lowerHalf = targetingKeyForBucket(4_999, 4_999);
        String upperHalf = targetingKeyForBucket(5_000, 5_000);

        EvaluationResult lower = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext(lowerHalf, Map.of("eligible", true)));
        EvaluationResult upper = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext(upperHalf, Map.of("eligible", true)));

        assertEquals("off", lower.variantKey().orElseThrow());
        assertEquals("on", upper.variantKey().orElseThrow());
        assertEquals(4_999, lower.metadata().get("rolloutBucket"));
        assertEquals(5_000, upper.metadata().get("rolloutBucket"));
    }

    @Test
    void missingTargetingKeyReturnsExplicitDefaultFallback() {
        FlagDefinition flag = booleanFlag(
                true,
                List.of(new TargetingRule(
                        0,
                        List.of(new Condition("eligible", ConditionOperator.EQUALS, true)),
                        new RuleResult.Rollout(List.of(
                                new Allocation("off", 5_000),
                                new Allocation("on", 5_000))))));

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext(null, Map.of("eligible", true)));

        assertEquals(EvaluationReason.ERROR, result.reason());
        assertEquals(EvaluationErrorCode.TARGETING_KEY_MISSING, result.errorCode().orElseThrow());
        assertEquals("off", result.variantKey().orElseThrow());
        assertEquals("DEFAULT_VARIANT", result.metadata().get("fallback"));
    }

    @Test
    void invalidContextTypeReturnsExplicitDefaultFallback() {
        FlagDefinition flag = booleanFlag(
                true,
                List.of(new TargetingRule(
                        0,
                        List.of(new Condition("age", ConditionOperator.GREATER_THAN, 18)),
                        new RuleResult.Variant("on"))));

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext("alice", Map.of("age", "eighteen")));

        assertEquals(EvaluationReason.ERROR, result.reason());
        assertEquals(EvaluationErrorCode.INVALID_CONTEXT, result.errorCode().orElseThrow());
        assertEquals("off", result.variantKey().orElseThrow());
    }

    @Test
    void typeMismatchReturnsNoValue() {
        EvaluationResult result = engine.evaluate(
                booleanFlag(true, List.of()), ValueType.STRING, EvaluationContext.empty());

        assertEquals(EvaluationReason.ERROR, result.reason());
        assertEquals(EvaluationErrorCode.TYPE_MISMATCH, result.errorCode().orElseThrow());
        assertTrue(result.value().isEmpty());
        assertTrue(result.variantKey().isEmpty());
    }

    @Test
    void rejectsAmbiguousOrInvalidModelsAtConstructionTime() {
        assertThrows(IllegalArgumentException.class, () -> new TargetingRule(
                0, List.of(), new RuleResult.Variant("on")));
        assertThrows(IllegalArgumentException.class, () -> new RuleResult.Rollout(List.of(
                new Allocation("off", 4_999), new Allocation("on", 5_000))));
        assertThrows(IllegalArgumentException.class, () -> ConditionOperator.parse("REGEX"));
        assertThrows(IllegalArgumentException.class, () -> booleanFlag(
                true,
                List.of(
                        new TargetingRule(
                                0,
                                List.of(new Condition("plan", ConditionOperator.EXISTS, null)),
                                new RuleResult.Variant("on")),
                        new TargetingRule(
                                0,
                                List.of(new Condition("country", ConditionOperator.EXISTS, null)),
                                new RuleResult.Variant("off")))));
    }

    private FlagDefinition booleanFlag(boolean enabled, List<TargetingRule> rules) {
        return new FlagDefinition(
                "checkout-v2",
                ValueType.BOOLEAN,
                enabled,
                "off",
                "checkout-seed",
                List.of(new Variant("off", booleanValue(false)), new Variant("on", booleanValue(true))),
                rules);
    }

    private String targetingKeyForBucket(int minimum, int maximum) {
        DeterministicRollout rollout = new DeterministicRollout();
        for (int index = 0; index < 100_000; index++) {
            String candidate = "boundary-" + index;
            int bucket = rollout.bucket(candidate, "checkout-v2", "checkout-seed");
            if (bucket >= minimum && bucket <= maximum) {
                return candidate;
            }
        }
        throw new AssertionError("could not find bucket in requested range");
    }
}
