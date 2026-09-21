package io.github.dlsrnjs125.switchboard.demo;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import java.nio.file.Path;
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
        api.setProvider(new SwitchboardProvider(config));
        Runtime.getRuntime().addShutdownHook(new Thread(api::shutdown));

        Client client = api.getClient(APPLICATION_NAME);
        boolean checkoutV2 = checkoutV2(client, "demo-customer", "standard");
        System.out.println(APPLICATION_NAME + " checkout-v2=" + checkoutV2);
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
}
