package io.github.dlsrnjs125.switchboard.demo;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Value;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProvider;
import io.github.dlsrnjs125.switchboard.sdk.SwitchboardProviderConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in evidence instrumentation for the demo; never installed in the SDK. */
final class EvidenceProbe {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    SwitchboardProvider provider(SwitchboardProviderConfig config) {
        return new SwitchboardProvider(config, meters, ObservationRegistry.NOOP);
    }

    boolean evaluate(Client client, String plan) {
        var details = client.getBooleanDetails("checkout-v2", false,
                new ImmutableContext("demo-customer", Map.of("plan", new Value(plan))));
        if (details.getErrorCode() != null) {
            throw new IllegalStateException("runtime evaluation failed: " + details.getErrorCode());
        }
        return details.getValue();
    }

    void record(int iteration, SwitchboardProvider provider) {
        var staleTimers = meters.find("switchboard.sdk.ready.stale.duration").timers();
        double staleMillis = staleTimers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        long staleIntervals = staleTimers.stream().mapToLong(t -> t.count()).sum();
        double reconnects = meters.find("switchboard.sdk.reconnect.total").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        double snapshots = meters.find("switchboard.sdk.snapshot.apply.total").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        System.out.printf(Locale.ROOT,
                "runtime-evidence iteration=%d monotonicNanos=%d state=%s version=%d reconnects=%.0f snapshots=%.0f staleIntervals=%d staleMillis=%.6f%n",
                iteration, System.nanoTime(), provider.switchboardState(), provider.lastAppliedVersion(),
                reconnects, snapshots, staleIntervals, staleMillis);
    }
}
