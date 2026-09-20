package io.github.dlsrnjs125.switchboard.controlplane.domain;

import java.util.List;

public class DomainException extends RuntimeException {
    private final String code;
    private final List<Violation> violations;

    public DomainException(String code, String message) {
        this(code, message, List.of());
    }

    public DomainException(String code, String message, List<Violation> violations) {
        super(message);
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public String code() {
        return code;
    }

    public List<Violation> violations() {
        return violations;
    }

    public record Violation(String field, String reason) {
    }

    public static DomainException notFound() {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found");
    }

    public static DomainException forbiddenRole() {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found");
    }

    public static DomainException invalid(String field, String reason) {
        return new DomainException("VALIDATION_FAILED", "Request validation failed", List.of(new Violation(field, reason)));
    }

    public static DomainException environmentVersionConflict() {
        return new DomainException(
                "ENVIRONMENT_VERSION_CONFLICT", "Expected environment version does not match");
    }
}
