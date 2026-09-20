package io.github.dlsrnjs125.switchboard.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TypedFlagEvaluationTest {
    @ParameterizedTest
    @MethodSource("typedValues")
    void evaluatesEverySupportedValueType(ValueType type, FlagValue value) {
        FlagDefinition flag = new FlagDefinition(
                "typed.flag",
                type,
                true,
                "default",
                "typed-seed",
                List.of(new Variant("default", value)),
                List.of());

        EvaluationResult result = new EvaluationEngine().evaluate(flag, type, EvaluationContext.empty());

        assertEquals(value, result.value().orElseThrow());
        assertEquals(EvaluationReason.DEFAULT, result.reason());
    }

    @ParameterizedTest
    @MethodSource("mismatchedValues")
    void rejectsVariantWhoseValueDoesNotMatchFlagType(ValueType type, FlagValue value) {
        assertThrows(IllegalArgumentException.class, () -> new FlagDefinition(
                "typed.flag",
                type,
                true,
                "default",
                "typed-seed",
                List.of(new Variant("default", value)),
                List.of()));
    }

    static Stream<Arguments> typedValues() {
        return Stream.of(
                Arguments.of(ValueType.BOOLEAN, FlagValue.booleanValue(true)),
                Arguments.of(ValueType.STRING, FlagValue.stringValue("enabled")),
                Arguments.of(ValueType.NUMBER, FlagValue.numberValue(new BigDecimal("42.5"))),
                Arguments.of(ValueType.OBJECT, FlagValue.objectValue(Map.of("mode", "safe", "limit", 10))));
    }

    static Stream<Arguments> mismatchedValues() {
        return Stream.of(
                Arguments.of(ValueType.BOOLEAN, FlagValue.stringValue("true")),
                Arguments.of(ValueType.STRING, FlagValue.numberValue(1)),
                Arguments.of(ValueType.NUMBER, FlagValue.booleanValue(true)),
                Arguments.of(ValueType.OBJECT, FlagValue.stringValue("{}")));
    }
}
