package io.github.dlsrnjs125.switchboard.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ImmutableJson {
    private ImmutableJson() {}

    static Map<String, Object> copyObject(Map<String, ?> source, String valueName) {
        Objects.requireNonNull(source, valueName);
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, valueName + " key"), copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                    requireStringKey(key), copyValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("values must contain only JSON-compatible data");
    }

    private static String requireStringKey(Object key) {
        if (!(key instanceof String stringKey)) {
            throw new IllegalArgumentException("object keys must be strings");
        }
        return stringKey;
    }
}
