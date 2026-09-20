package io.github.dlsrnjs125.switchboard.evaluation;

import static io.github.dlsrnjs125.switchboard.evaluation.FlagValue.booleanValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImmutableInputTest {
    private final EvaluationEngine engine = new EvaluationEngine();

    @Test
    void conditionOperandIsUnaffectedBySourceCollectionMutation() {
        List<String> countries = new ArrayList<>(List.of("KR"));
        Condition condition = new Condition("country", ConditionOperator.IN, countries);
        FlagDefinition flag = booleanFlag(condition);

        countries.clear();
        countries.add("US");

        EvaluationResult result = engine.evaluate(
                flag, ValueType.BOOLEAN, new EvaluationContext("alice", Map.of("country", "KR")));

        assertEquals("on", result.variantKey().orElseThrow());
        @SuppressWarnings("unchecked")
        List<Object> capturedOperand = (List<Object>) condition.operand();
        assertThrows(UnsupportedOperationException.class, capturedOperand::clear);
    }

    @Test
    void contextIsUnaffectedByNestedSourceMutation() {
        List<String> roles = new ArrayList<>(List.of("admin"));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("roles", roles);
        EvaluationContext context = new EvaluationContext("alice", Map.of("profile", profile));
        Condition condition = new Condition(
                "profile", ConditionOperator.EQUALS, Map.of("roles", List.of("admin")));
        FlagDefinition flag = booleanFlag(condition);

        roles.add("viewer");
        profile.put("tier", "free");

        EvaluationResult result = engine.evaluate(flag, ValueType.BOOLEAN, context);

        assertEquals("on", result.variantKey().orElseThrow());
        @SuppressWarnings("unchecked")
        Map<String, Object> capturedProfile = (Map<String, Object>) context.attributes().get("profile");
        assertThrows(UnsupportedOperationException.class, () -> capturedProfile.put("tier", "paid"));
    }

    private FlagDefinition booleanFlag(Condition condition) {
        return new FlagDefinition(
                "checkout-v2",
                ValueType.BOOLEAN,
                true,
                "off",
                "checkout-seed",
                List.of(new Variant("off", booleanValue(false)), new Variant("on", booleanValue(true))),
                List.of(new TargetingRule(0, List.of(condition), new RuleResult.Variant("on"))));
    }
}
