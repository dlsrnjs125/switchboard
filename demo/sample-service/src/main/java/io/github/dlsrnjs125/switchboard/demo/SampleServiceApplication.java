package io.github.dlsrnjs125.switchboard.demo;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

public final class SampleServiceApplication {
    public static final String APPLICATION_NAME = "switchboard-sample-service";

    private SampleServiceApplication() {
    }

    public static void main(String[] args) {
        SwitchboardProviderConfig config = SwitchboardProviderConfig.defaults(
                environment("SWITCHBOARD_DISTRIBUTION_ENDPOINT", "localhost:9090"),
                environment("SWITCHBOARD_SERVICE_CREDENTIAL", "credential-not-configured"),
                environment("SWITCHBOARD_CLIENT_APPLICATION", "sample-service"),
                environment("SWITCHBOARD_PROJECT", "checkout"),
                environment("SWITCHBOARD_ENVIRONMENT", "production"),
                Path.of(environment("SWITCHBOARD_LKG_PATH", ".switchboard/lkg.json")));
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        SimpleMeterRegistry probeMeters = new SimpleMeterRegistry();
        boolean runtimeEvidence = Boolean.parseBoolean(environment("SWITCHBOARD_RUNTIME_EVIDENCE", "false"));
        SwitchboardProvider provider = runtimeEvidence
                ? new SwitchboardProvider(config, probeMeters, ObservationRegistry.NOOP)
                : new SwitchboardProvider(config);
        api.setProvider(provider);
        Runtime.getRuntime().addShutdownHook(new Thread(api::shutdown));

        Client client = api.getClient(APPLICATION_NAME);
        String plan = environment("SWITCHBOARD_DEMO_PLAN", "standard");
        int iterations = positiveInteger("SWITCHBOARD_EVALUATION_ITERATIONS", 1);
        long intervalMillis = nonNegativeLong("SWITCHBOARD_EVALUATION_INTERVAL_MILLIS", 0);
        String expected = System.getenv("SWITCHBOARD_EXPECTED_BOOLEAN");
        List<Long> expectedVersions = expectedVersions(System.getenv("SWITCHBOARD_EXPECTED_SNAPSHOT_VERSIONS"));
        Duration readyTimeout = Duration.ofSeconds(
                positiveInteger("SWITCHBOARD_READY_TIMEOUT_SECONDS", 30));
        if (expected != null || !expectedVersions.isEmpty()) {
            awaitReady(provider, readyTimeout);
        }
        for (long expectedVersion : expectedVersions) {
            awaitSnapshotVersion(provider, expectedVersion, readyTimeout);
            System.out.println(APPLICATION_NAME + " observed-snapshot-version=" + expectedVersion);
        }
        for (int iteration = 1; iteration <= iterations; iteration++) {
            boolean checkoutV2;
            if (runtimeEvidence) {
                var details = client.getBooleanDetails("checkout-v2", false,
                        new ImmutableContext("demo-customer", Map.of("plan", new Value(plan))));
                if (details.getErrorCode() != null) {
                    throw new IllegalStateException("runtime evaluation failed: " + details.getErrorCode());
                }
                checkoutV2 = details.getValue();
            } else {
                checkoutV2 = checkoutV2(client, "demo-customer", plan);
            }
            System.out.println(APPLICATION_NAME + " iteration=" + iteration + " checkout-v2=" + checkoutV2);
            if (runtimeEvidence) {
                var staleTimers = probeMeters.find("switchboard.sdk.ready.stale.duration").timers();
                double staleMillis = staleTimers.stream()
                        .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
                long staleIntervals = staleTimers.stream().mapToLong(timer -> timer.count()).sum();
                double reconnects = probeMeters.find("switchboard.sdk.reconnect.total").counters().stream()
                        .mapToDouble(counter -> counter.count()).sum();
                double snapshots = probeMeters.find("switchboard.sdk.snapshot.apply.total").counters().stream()
                        .mapToDouble(counter -> counter.count()).sum();
                System.out.printf(Locale.ROOT,
                        "runtime-evidence iteration=%d monotonicNanos=%d state=%s version=%d reconnects=%.0f snapshots=%.0f staleIntervals=%d staleMillis=%.6f%n",
                        iteration, System.nanoTime(), provider.switchboardState(), provider.lastAppliedVersion(),
                        reconnects, snapshots, staleIntervals, staleMillis);
            }
            if (expected != null && checkoutV2 != Boolean.parseBoolean(expected)) {
                throw new IllegalStateException("checkout-v2 did not match expected value " + expected);
            }
            if (iteration < iterations && intervalMillis > 0) {
                sleep(Duration.ofMillis(intervalMillis));
            }
        }
        api.shutdown();
    }

    static boolean checkoutV2(Client client, String customerId, String plan) {
        return client.getBooleanValue(
                "checkout-v2",
                false,
                new ImmutableContext(customerId, Map.of("plan", new Value(plan))));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int positiveInteger(String name, int fallback) {
        int value = Integer.parseInt(environment(name, Integer.toString(fallback)));
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long nonNegativeLong(String name, long fallback) {
        long value = Long.parseLong(environment(name, Long.toString(fallback)));
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static List<Long> expectedVersions(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .map(value -> {
                    long version = Long.parseLong(value);
                    if (version < 1) {
                        throw new IllegalArgumentException(
                                "SWITCHBOARD_EXPECTED_SNAPSHOT_VERSIONS must contain positive versions");
                    }
                    return version;
                })
                .toList();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation probe interrupted", exception);
        }
    }

    private static void awaitReady(SwitchboardProvider provider, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (provider.switchboardState() != SwitchboardProviderState.READY
                && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(50));
        }
        if (provider.switchboardState() != SwitchboardProviderState.READY) {
            throw new IllegalStateException("provider did not become READY within " + timeout);
        }
    }

    private static void awaitSnapshotVersion(
            SwitchboardProvider provider,
            long expectedVersion,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (provider.lastAppliedVersion() != expectedVersion && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(50));
        }
        if (provider.lastAppliedVersion() != expectedVersion) {
            throw new IllegalStateException("provider did not apply snapshot " + expectedVersion
                    + " within " + timeout + "; current=" + provider.lastAppliedVersion());
        }
    }
}
