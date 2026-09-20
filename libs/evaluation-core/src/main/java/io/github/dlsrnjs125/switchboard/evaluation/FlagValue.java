package io.github.dlsrnjs125.switchboard.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
            value = immutableObject(value);
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

    private static Map<String, Object> immutableObject(Map<String, Object> source) {
        Objects.requireNonNull(source, "value");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "object key"), immutableJsonValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                    Objects.requireNonNull(key, "object key").toString(), immutableJsonValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("object values must contain only JSON-compatible data");
    }
}
