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
import java.util.Map;

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
        SwitchboardProvider provider = new SwitchboardProvider(config);
        api.setProvider(provider);
        Runtime.getRuntime().addShutdownHook(new Thread(api::shutdown));

        Client client = api.getClient(APPLICATION_NAME);
        String plan = environment("SWITCHBOARD_DEMO_PLAN", "standard");
        int iterations = positiveInteger("SWITCHBOARD_EVALUATION_ITERATIONS", 1);
        long intervalMillis = nonNegativeLong("SWITCHBOARD_EVALUATION_INTERVAL_MILLIS", 0);
        String expected = System.getenv("SWITCHBOARD_EXPECTED_BOOLEAN");
        if (expected != null) {
            awaitReady(provider, Duration.ofSeconds(
                    positiveInteger("SWITCHBOARD_READY_TIMEOUT_SECONDS", 30)));
        }
        for (int iteration = 1; iteration <= iterations; iteration++) {
            boolean checkoutV2 = checkoutV2(client, "demo-customer", plan);
            System.out.println(APPLICATION_NAME + " iteration=" + iteration + " checkout-v2=" + checkoutV2);
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
}
