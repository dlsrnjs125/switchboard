package io.github.dlsrnjs125.switchboard.evaluation;

import io.github.dlsrnjs125.switchboard.evaluation.EvaluationContext.Attribute;
import io.github.dlsrnjs125.switchboard.evaluation.FlagDefinition.Variant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class EvaluationEngine {
    private final DeterministicRollout rollout;

    public EvaluationEngine() {
        this(new DeterministicRollout());
    }

    public EvaluationEngine(DeterministicRollout rollout) {
        this.rollout = Objects.requireNonNull(rollout, "rollout");
    }

    public EvaluationResult evaluate(FlagDefinition flag, ValueType requestedType, EvaluationContext context) {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(requestedType, "requestedType");
        context = context == null ? EvaluationContext.empty() : context;

        if (requestedType != flag.valueType()) {
            return new EvaluationResult(
                    Optional.empty(),
                    Optional.empty(),
                    EvaluationReason.ERROR,
                    Optional.of(EvaluationErrorCode.TYPE_MISMATCH),
                    Optional.of("requested " + requestedType + " but flag is " + flag.valueType()),
                    Map.of());
        }
        if (!flag.enabled()) {
            return resolved(flag.defaultVariant(), EvaluationReason.DISABLED, Map.of());
        }

        for (TargetingRule rule : flag.rules()) {
            RuleMatch match = matches(rule, context);
            if (match.errorMessage() != null) {
                return fallback(
                        flag,
                        EvaluationErrorCode.INVALID_CONTEXT,
                        match.errorMessage(),
                        Map.of("rulePriority", rule.priority()));
            }
            if (!match.matches()) {
                continue;
            }
            if (rule.result() instanceof RuleResult.Variant fixed) {
                return resolved(
                        flag.variant(fixed.variantKey()).orElseThrow(),
                        EvaluationReason.TARGETING_MATCH,
                        Map.of("rulePriority", rule.priority()));
            }
            RuleResult.Rollout split = (RuleResult.Rollout) rule.result();
            Optional<String> targetingKey = context.optionalTargetingKey();
            if (targetingKey.isEmpty()) {
                return fallback(
                        flag,
                        EvaluationErrorCode.TARGETING_KEY_MISSING,
                        "targetingKey is required for percentage rollout",
                        Map.of("rulePriority", rule.priority()));
            }
            int bucket = rollout.bucket(targetingKey.get(), flag.flagKey(), flag.rolloutSeed());
            int upperExclusive = 0;
            for (RuleResult.Allocation allocation : split.allocations()) {
                upperExclusive += allocation.basisPoints();
                if (bucket < upperExclusive) {
                    return resolved(
                            flag.variant(allocation.variantKey()).orElseThrow(),
                            EvaluationReason.SPLIT,
                            Map.of("rulePriority", rule.priority(), "rolloutBucket", bucket));
                }
            }
            throw new IllegalStateException("validated rollout did not resolve bucket " + bucket);
        }
        return resolved(flag.defaultVariant(), EvaluationReason.DEFAULT, Map.of());
    }

    private RuleMatch matches(TargetingRule rule, EvaluationContext context) {
        for (Condition condition : rule.conditions()) {
            ConditionMatch conditionMatch = evaluateCondition(condition, context.lookup(condition.attribute()));
            if (conditionMatch.errorMessage() != null) {
                return new RuleMatch(false, conditionMatch.errorMessage());
            }
            if (!conditionMatch.matches()) {
                return new RuleMatch(false, null);
            }
        }
        return new RuleMatch(true, null);
    }

    private ConditionMatch evaluateCondition(Condition condition, Attribute attribute) {
        ConditionOperator operator = condition.operator();
        if (operator == ConditionOperator.EXISTS) {
            return matched(attribute.present());
        }
        if (operator == ConditionOperator.NOT_EXISTS) {
            return matched(!attribute.present());
        }
        if (!attribute.present()) {
            return matched(false);
        }

        Object actual = attribute.value();
        Object operand = condition.operand();
        return switch (operator) {
            case EQUALS -> matched(equalValues(actual, operand));
            case NOT_EQUALS -> matched(!equalValues(actual, operand));
            case IN -> matched(((Collection<?>) operand).stream().anyMatch(item -> equalValues(actual, item)));
            case NOT_IN -> matched(((Collection<?>) operand).stream().noneMatch(item -> equalValues(actual, item)));
            case GREATER_THAN -> compare(actual, operand, comparison -> comparison > 0, condition);
            case GREATER_THAN_OR_EQUALS -> compare(actual, operand, comparison -> comparison >= 0, condition);
            case LESS_THAN -> compare(actual, operand, comparison -> comparison < 0, condition);
            case LESS_THAN_OR_EQUALS -> compare(actual, operand, comparison -> comparison <= 0, condition);
            case STARTS_WITH -> stringCompare(actual, operand, StringOperation.STARTS_WITH, condition);
            case ENDS_WITH -> stringCompare(actual, operand, StringOperation.ENDS_WITH, condition);
            case CONTAINS -> stringCompare(actual, operand, StringOperation.CONTAINS, condition);
            case EXISTS, NOT_EXISTS -> throw new IllegalStateException("existence operator already handled");
        };
    }

    private ConditionMatch compare(
            Object actual, Object operand, IntPredicate predicate, Condition condition) {
        if (!(actual instanceof Number) || !(operand instanceof Number)) {
            return invalid(condition, "numeric comparison requires number values");
        }
        int comparison = decimal(actual).compareTo(decimal(operand));
        return matched(predicate.test(comparison));
    }

    private ConditionMatch stringCompare(
            Object actual, Object operand, StringOperation operation, Condition condition) {
        if (!(actual instanceof String actualString) || !(operand instanceof String operandString)) {
            return invalid(condition, "string operator requires string values");
        }
        return matched(switch (operation) {
            case STARTS_WITH -> actualString.startsWith(operandString);
            case ENDS_WITH -> actualString.endsWith(operandString);
            case CONTAINS -> actualString.contains(operandString);
        });
    }

    private boolean equalValues(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        return Objects.equals(left, right);
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private ConditionMatch invalid(Condition condition, String reason) {
        return new ConditionMatch(false, condition.attribute() + " " + condition.operator() + ": " + reason);
    }

    private ConditionMatch matched(boolean matches) {
        return new ConditionMatch(matches, null);
    }

    private EvaluationResult resolved(Variant variant, EvaluationReason reason, Map<String, Object> metadata) {
        return new EvaluationResult(
                Optional.of(variant.value()),
                Optional.of(variant.key()),
                reason,
                Optional.empty(),
                Optional.empty(),
                metadata);
    }

    private EvaluationResult fallback(
            FlagDefinition flag,
            EvaluationErrorCode errorCode,
            String message,
            Map<String, Object> metadata) {
        Variant fallback = flag.defaultVariant();
        Map<String, Object> details = new LinkedHashMap<>(metadata);
        details.put("fallback", "DEFAULT_VARIANT");
        return new EvaluationResult(
                Optional.of(fallback.value()),
                Optional.of(fallback.key()),
                EvaluationReason.ERROR,
                Optional.of(errorCode),
                Optional.of(message),
                details);
    }

    private record RuleMatch(boolean matches, String errorMessage) {
    }

    private record ConditionMatch(boolean matches, String errorMessage) {
    }

    @FunctionalInterface
    private interface IntPredicate {
        boolean test(int value);
    }

    private enum StringOperation {
        STARTS_WITH,
        ENDS_WITH,
        CONTAINS
    }
}
