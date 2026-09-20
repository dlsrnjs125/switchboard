package io.github.dlsrnjs125.switchboard.controlplane.api;

import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException;
import io.github.dlsrnjs125.switchboard.controlplane.domain.DomainException.Violation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ErrorResponse> handleDomain(DomainException exception, HttpServletRequest request) {
        HttpStatus status = exception.code().equals("RESOURCE_NOT_FOUND")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new ErrorResponse(
                exception.code(), exception.getMessage(), correlationId(request), exception.violations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<Violation> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "VALIDATION_FAILED", "Request validation failed", correlationId(request), details));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleConflict(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "RESOURCE_CONFLICT", "Resource conflicts with existing state", correlationId(request), List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "VALIDATION_FAILED", "Request body is invalid", correlationId(request), List.of()));
    }

    private UUID correlationId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Correlation-Id");
        if (supplied != null) {
            try {
                return UUID.fromString(supplied);
            } catch (IllegalArgumentException ignored) {
                // Invalid caller-provided IDs are replaced so the error response remains stable.
            }
        }
        return UUID.randomUUID();
    }

    public record ErrorResponse(String code, String message, UUID correlationId, List<Violation> details) {
    }
}
