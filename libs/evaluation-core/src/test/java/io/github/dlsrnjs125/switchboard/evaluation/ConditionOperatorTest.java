package io.github.dlsrnjs125.switchboard.evaluation;

import static io.github.dlsrnjs125.switchboard.evaluation.FlagValue.booleanValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConditionOperatorTest {
    private final EvaluationEngine engine = new EvaluationEngine();

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchingOperators")
    void evaluatesEveryCanonicalOperator(
            ConditionOperator operator,
            Object actual,
            Object operand,
            boolean attributePresent) {
        Condition condition = new Condition("attribute", operator, operand);
        FlagDefinition flag = new FlagDefinition(
                "operator-test",
                ValueType.BOOLEAN,
                true,
                "off",
                "operator-seed",
                List.of(new Variant("off", booleanValue(false)), new Variant("on", booleanValue(true))),
                List.of(new TargetingRule(0, List.of(condition), new RuleResult.Variant("on"))));
        Map<String, Object> attributes = attributePresent ? Map.of("attribute", actual) : Map.of();

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext("subject", attributes));

        assertEquals("on", result.variantKey().orElseThrow());
        assertEquals(EvaluationReason.TARGETING_MATCH, result.reason());
    }

    static Stream<Arguments> matchingOperators() {
        return Stream.of(
                Arguments.of(ConditionOperator.EQUALS, 10, 10L, true),
                Arguments.of(ConditionOperator.NOT_EQUALS, "free", "premium", true),
                Arguments.of(ConditionOperator.IN, "KR", List.of("KR", "US"), true),
                Arguments.of(ConditionOperator.NOT_IN, "JP", List.of("KR", "US"), true),
                Arguments.of(ConditionOperator.GREATER_THAN, 20, 18, true),
                Arguments.of(ConditionOperator.GREATER_THAN_OR_EQUALS, 18, 18.0, true),
                Arguments.of(ConditionOperator.LESS_THAN, 17, 18, true),
                Arguments.of(ConditionOperator.LESS_THAN_OR_EQUALS, 18, 18, true),
                Arguments.of(ConditionOperator.EXISTS, "anything", null, true),
                Arguments.of(ConditionOperator.NOT_EXISTS, null, null, false),
                Arguments.of(ConditionOperator.STARTS_WITH, "premium-plus", "premium", true),
                Arguments.of(ConditionOperator.ENDS_WITH, "user@example.com", "@example.com", true),
                Arguments.of(ConditionOperator.CONTAINS, "feature-flags", "flag", true));
    }
}
