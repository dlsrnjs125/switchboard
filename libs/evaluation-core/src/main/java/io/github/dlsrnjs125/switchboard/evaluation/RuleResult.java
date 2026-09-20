package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface RuleResult permits RuleResult.Variant, RuleResult.Rollout {
    record Variant(String variantKey) implements RuleResult {
        public Variant {
            if (variantKey == null || variantKey.isBlank()) {
                throw new IllegalArgumentException("variantKey must not be blank");
            }
        }
    }

    record Rollout(List<Allocation> allocations) implements RuleResult {
        public Rollout {
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
            if (allocations.isEmpty()) {
                throw new IllegalArgumentException("rollout must contain at least one allocation");
            }
            Set<String> keys = new HashSet<>();
            int total = 0;
            for (Allocation allocation : allocations) {
                if (!keys.add(allocation.variantKey())) {
                    throw new IllegalArgumentException("rollout variant keys must be unique");
                }
                total = Math.addExact(total, allocation.basisPoints());
            }
            if (total != DeterministicRollout.BUCKET_COUNT) {
                throw new IllegalArgumentException("rollout allocations must total 10000 basis points");
            }
        }
    }

    record Allocation(String variantKey, int basisPoints) {
        public Allocation {
            if (variantKey == null || variantKey.isBlank()) {
                throw new IllegalArgumentException("allocation variantKey must not be blank");
            }
            if (basisPoints < 1 || basisPoints > DeterministicRollout.BUCKET_COUNT) {
                throw new IllegalArgumentException("basisPoints must be between 1 and 10000");
            }
        }
    }
}
