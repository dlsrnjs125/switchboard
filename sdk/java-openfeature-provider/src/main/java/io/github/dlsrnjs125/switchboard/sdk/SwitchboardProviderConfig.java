package io.github.dlsrnjs125.switchboard.sdk;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public record SwitchboardProviderConfig(
        String endpoint,
        String bearerCredential,
        String clientApplicationKey,
        String projectKey,
        String environmentKey,
        Path lkgPath,
        Duration staleAfter,
        Duration maxLkgAge,
        Duration initialReconnectBackoff,
        Duration maxReconnectBackoff,
        double reconnectJitter,
        Clock clock) {
    public SwitchboardProviderConfig {
        endpoint = required("endpoint", endpoint);
        bearerCredential = required("bearerCredential", bearerCredential);
        clientApplicationKey = required("clientApplicationKey", clientApplicationKey);
        projectKey = required("projectKey", projectKey);
        environmentKey = required("environmentKey", environmentKey);
        Objects.requireNonNull(lkgPath, "lkgPath");
        staleAfter = positive("staleAfter", staleAfter);
        maxLkgAge = positive("maxLkgAge", maxLkgAge);
        initialReconnectBackoff = positive("initialReconnectBackoff", initialReconnectBackoff);
        maxReconnectBackoff = positive("maxReconnectBackoff", maxReconnectBackoff);
        if (maxReconnectBackoff.compareTo(initialReconnectBackoff) < 0) {
            throw new IllegalArgumentException("maxReconnectBackoff must be at least initialReconnectBackoff");
        }
        if (reconnectJitter < 0 || reconnectJitter > 1) {
            throw new IllegalArgumentException("reconnectJitter must be between 0 and 1");
        }
        Objects.requireNonNull(clock, "clock");
    }

    public static SwitchboardProviderConfig defaults(
            String endpoint,
            String bearerCredential,
            String clientApplicationKey,
            String projectKey,
            String environmentKey,
            Path lkgPath) {
        return new SwitchboardProviderConfig(
                endpoint,
                bearerCredential,
                clientApplicationKey,
                projectKey,
                environmentKey,
                lkgPath,
                Duration.ofSeconds(30),
                Duration.ofDays(7),
                Duration.ofMillis(250),
                Duration.ofSeconds(30),
                0.2,
                Clock.systemUTC());
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration positive(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
