package io.github.dlsrnjs125.switchboard.evaluation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record EvaluationResult(
        Optional<FlagValue> value,
        Optional<String> variantKey,
        EvaluationReason reason,
        Optional<EvaluationErrorCode> errorCode,
        Optional<String> errorMessage,
        Map<String, Object> metadata) {
    public EvaluationResult {
        value = value == null ? Optional.empty() : value;
        variantKey = variantKey == null ? Optional.empty() : variantKey;
        Objects.requireNonNull(reason, "reason");
        errorCode = errorCode == null ? Optional.empty() : errorCode;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean hasError() {
        return errorCode.isPresent();
    }
}
