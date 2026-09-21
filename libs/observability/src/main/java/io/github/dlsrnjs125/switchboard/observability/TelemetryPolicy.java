package io.github.dlsrnjs125.switchboard.observability;

import io.micrometer.core.instrument.Tags;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class TelemetryPolicy {
    private static final Pattern METRIC_NAME = Pattern.compile("^switchboard\\.[a-z][a-z0-9_.]*$");
    private static final Set<String> METRIC_TAG_ALLOWLIST = Set.of(
            "component", "operation", "outcome", "reason", "error_code", "provider_state",
            "event_type", "result_type");
    private static final Set<String> FORBIDDEN_ATTRIBUTE_PARTS = Set.of(
            "targeting", "userid", "user_id", "credential", "secret", "token", "password", "payload");

    private TelemetryPolicy() {
    }

    public static String metricName(String name) {
        if (name == null || !METRIC_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("metric name must use the switchboard.* lowercase convention");
        }
        return name;
    }

    public static Tags metricTags(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("metric tags must be key/value pairs");
        }
        for (int index = 0; index < keyValues.length; index += 2) {
            String key = normalized(keyValues[index]);
            if (!METRIC_TAG_ALLOWLIST.contains(key)) {
                throw new IllegalArgumentException("metric tag is not allowlisted: " + key);
            }
            rejectSensitive(key, keyValues[index + 1]);
        }
        return Tags.of(keyValues);
    }

    public static String traceAttribute(String key, Object value) {
        rejectSensitive(normalized(key), value == null ? "" : value.toString());
        return value == null ? "" : value.toString();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("telemetry key must not be blank");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static void rejectSensitive(String key, String value) {
        String normalizedKey = normalized(key).replace("-", "_");
        for (String forbidden : FORBIDDEN_ATTRIBUTE_PARTS) {
            if (normalizedKey.contains(forbidden)) {
                throw new IllegalArgumentException("sensitive telemetry attribute is forbidden: " + key);
            }
        }
        if (value != null && value.regionMatches(true, 0, "bearer ", 0, 7)) {
            throw new IllegalArgumentException("bearer credentials are forbidden in telemetry");
        }
    }
}
