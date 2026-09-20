package io.github.dlsrnjs125.switchboard.evaluation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public sealed interface FlagValue
        permits FlagValue.BooleanValue, FlagValue.StringValue, FlagValue.NumberValue, FlagValue.ObjectValue {
    ValueType type();

    record BooleanValue(boolean value) implements FlagValue {
        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record StringValue(String value) implements FlagValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType type() {
            return ValueType.STRING;
        }
    }

    record NumberValue(BigDecimal value) implements FlagValue {
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType type() {
            return ValueType.NUMBER;
        }
    }

    record ObjectValue(Map<String, Object> value) implements FlagValue {
        public ObjectValue {
            value = ImmutableJson.copyObject(value, "value");
        }

        @Override
        public ValueType type() {
            return ValueType.OBJECT;
        }
    }

    static BooleanValue booleanValue(boolean value) {
        return new BooleanValue(value);
    }

    static StringValue stringValue(String value) {
        return new StringValue(value);
    }

    static NumberValue numberValue(BigDecimal value) {
        return new NumberValue(value);
    }

    static NumberValue numberValue(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    static ObjectValue objectValue(Map<String, Object> value) {
        return new ObjectValue(value);
    }

}
