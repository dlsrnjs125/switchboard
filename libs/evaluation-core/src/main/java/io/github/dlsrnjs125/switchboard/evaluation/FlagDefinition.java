package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class FlagDefinition {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$");

    private final String flagKey;
    private final ValueType valueType;
    private final boolean enabled;
    private final String defaultVariantKey;
    private final String rolloutSeed;
    private final Map<String, Variant> variants;
    private final List<TargetingRule> rules;

    public FlagDefinition(
            String flagKey,
            ValueType valueType,
            boolean enabled,
            String defaultVariantKey,
            String rolloutSeed,
            List<Variant> variants,
            List<TargetingRule> rules) {
        this.flagKey = requireKey("flagKey", flagKey);
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.enabled = enabled;
        this.defaultVariantKey = requireKey("defaultVariantKey", defaultVariantKey);
        if (rolloutSeed == null || rolloutSeed.isBlank()) {
            throw new IllegalArgumentException("rolloutSeed must not be blank");
        }
        this.rolloutSeed = rolloutSeed;
        this.variants = indexVariants(variants);
        if (!this.variants.containsKey(defaultVariantKey)) {
            throw new IllegalArgumentException("defaultVariantKey must reference a declared variant");
        }
        this.rules = validateAndOrderRules(rules);
    }

    public String flagKey() {
        return flagKey;
    }

    public ValueType valueType() {
        return valueType;
    }

    public boolean enabled() {
        return enabled;
    }

    public String defaultVariantKey() {
        return defaultVariantKey;
    }

    public String rolloutSeed() {
        return rolloutSeed;
    }

    public Map<String, Variant> variants() {
        return variants;
    }

    public List<TargetingRule> rules() {
        return rules;
    }

    public Optional<Variant> variant(String key) {
        return Optional.ofNullable(variants.get(key));
    }

    public Variant defaultVariant() {
        return variants.get(defaultVariantKey);
    }

    private Map<String, Variant> indexVariants(List<Variant> source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("flag must contain at least one variant");
        }
        Map<String, Variant> indexed = new LinkedHashMap<>();
        for (Variant variant : source) {
            Objects.requireNonNull(variant, "variant");
            if (variant.value().type() != valueType) {
                throw new IllegalArgumentException("variant value must match flag valueType " + valueType);
            }
            if (indexed.putIfAbsent(variant.key(), variant) != null) {
                throw new IllegalArgumentException("variant keys must be unique");
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private List<TargetingRule> validateAndOrderRules(List<TargetingRule> source) {
        List<TargetingRule> ordered = new ArrayList<>(source == null ? List.of() : source);
        ordered.sort(Comparator.comparingInt(TargetingRule::priority));
        Set<Integer> priorities = new HashSet<>();
        for (TargetingRule rule : ordered) {
            Objects.requireNonNull(rule, "rule");
            if (!priorities.add(rule.priority())) {
                throw new IllegalArgumentException("rule priorities must be unique");
            }
            if (rule.result() instanceof RuleResult.Variant fixed) {
                requireVariant(fixed.variantKey());
            } else if (rule.result() instanceof RuleResult.Rollout rollout) {
                rollout.allocations().forEach(allocation -> requireVariant(allocation.variantKey()));
            }
        }
        return List.copyOf(ordered);
    }

    private void requireVariant(String key) {
        if (!variants.containsKey(key)) {
            throw new IllegalArgumentException("rule result references an unknown variant: " + key);
        }
    }

    private static String requireKey(String field, String value) {
        if (value == null || value.length() > 128 || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid key");
        }
        return value;
    }

    public record Variant(String key, FlagValue value) {
        public Variant {
            key = requireKey("variant key", key);
            Objects.requireNonNull(value, "value");
        }
    }
}
