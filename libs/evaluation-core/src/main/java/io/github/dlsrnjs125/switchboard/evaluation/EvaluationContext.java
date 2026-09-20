package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record EvaluationContext(String targetingKey, Map<String, Object> attributes) {
    public EvaluationContext {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "attribute key"), ImmutableJson.copyValue(value)));
        }
        attributes = Collections.unmodifiableMap(copy);
    }

    public static EvaluationContext empty() {
        return new EvaluationContext(null, Map.of());
    }

    public static EvaluationContext targeting(String targetingKey) {
        return new EvaluationContext(Objects.requireNonNull(targetingKey, "targetingKey"), Map.of());
    }

    public Optional<String> optionalTargetingKey() {
        return Optional.ofNullable(targetingKey).filter(key -> !key.isBlank());
    }

    Attribute lookup(String attribute) {
        if (attribute.equals("targetingKey")) {
            return targetingKey == null ? Attribute.missing() : Attribute.present(targetingKey);
        }
        return attributes.containsKey(attribute)
                ? Attribute.present(attributes.get(attribute))
                : Attribute.missing();
    }

    record Attribute(boolean present, Object value) {
        static Attribute present(Object value) {
            return new Attribute(true, value);
        }

        static Attribute missing() {
            return new Attribute(false, null);
        }
    }
}
