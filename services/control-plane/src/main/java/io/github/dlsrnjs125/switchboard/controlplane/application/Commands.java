package io.github.dlsrnjs125.switchboard.controlplane.application;

import tools.jackson.databind.JsonNode;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.EnvironmentType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.RuleResultType;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainTypes.ValueType;
import java.util.List;

public final class Commands {
    private Commands() {
    }

    public record CreateProject(String projectKey, String name) {
    }

    public record CreateEnvironment(String environmentKey, String name, EnvironmentType type) {
    }

    public record CreateFlag(String flagKey, ValueType valueType) {
    }

    public record CreateRevision(
            List<Variant> variants,
            String defaultVariantKey,
            String rolloutSeed,
            List<Rule> rules) {
        public CreateRevision {
            variants = variants == null ? List.of() : List.copyOf(variants);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record Variant(String key, JsonNode value) {
    }

    public record Rule(
            int priority,
            RuleResultType resultType,
            String resultVariantKey,
            List<Condition> conditions,
            List<Allocation> allocations) {
        public Rule {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
        }
    }

    public record Condition(int order, String attribute, String operator, JsonNode operand) {
    }

    public record Allocation(String variantKey, int basisPoints) {
    }
}
