package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.List;
import java.util.Objects;

public record TargetingRule(int priority, List<Condition> conditions, RuleResult result) {
    public TargetingRule {
        if (priority < 0) {
            throw new IllegalArgumentException("rule priority must be non-negative");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("targeting rule must contain at least one condition");
        }
        Objects.requireNonNull(result, "result");
    }
}
