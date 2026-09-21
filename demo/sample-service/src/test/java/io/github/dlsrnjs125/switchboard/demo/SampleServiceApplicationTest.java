package io.github.dlsrnjs125.switchboard.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SampleServiceApplicationTest {
    @AfterEach
    void closeOpenFeature() {
        OpenFeatureAPI.getInstance().shutdown();
    }

    @Test
    void exposesStableApplicationName() {
        assertEquals("switchboard-sample-service", SampleServiceApplication.APPLICATION_NAME);
    }

    @Test
    void applicationEvaluationUsesOnlyTheOpenFeatureClientApi() throws Exception {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait(new InMemoryProvider(Map.of(
                "checkout-v2", Flag.builder()
                        .variant("off", false)
                        .variant("on", true)
                        .defaultVariant("on")
                        .build())));
        Client client = api.getClient(SampleServiceApplication.APPLICATION_NAME);

        assertTrue(SampleServiceApplication.checkoutV2(client, "customer-1", "premium"));
    }

    @Test
    void parsesExpectedSnapshotVersionSequence() {
        assertEquals(
                java.util.List.of(1L, 2L),
                SampleServiceApplication.expectedVersions("1, 2"));
        assertTrue(SampleServiceApplication.expectedVersions(" ").isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> SampleServiceApplication.expectedVersions("0"));
    }
}
