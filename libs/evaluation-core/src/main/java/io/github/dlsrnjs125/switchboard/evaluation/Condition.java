package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.Collection;
import java.util.Objects;

public record Condition(String attribute, ConditionOperator operator, Object operand) {
    public Condition {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("condition attribute must not be blank");
        }
        Objects.requireNonNull(operator, "operator");
        boolean existence = operator == ConditionOperator.EXISTS || operator == ConditionOperator.NOT_EXISTS;
        if (existence && operand != null) {
            throw new IllegalArgumentException("existence operators must not declare an operand");
        }
        if (!existence && operand == null) {
            throw new IllegalArgumentException("operator " + operator + " requires an operand");
        }
        if ((operator == ConditionOperator.IN || operator == ConditionOperator.NOT_IN)
                && (!(operand instanceof Collection<?> values) || values.isEmpty())) {
            throw new IllegalArgumentException(operator + " requires a non-empty collection operand");
        }
        operand = ImmutableJson.copyValue(operand);
    }
}
