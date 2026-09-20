package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.Locale;

public enum ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    EXISTS,
    NOT_EXISTS,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS;

    public static ConditionOperator parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("condition operator must not be blank");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported condition operator: " + value, exception);
        }
    }
}
