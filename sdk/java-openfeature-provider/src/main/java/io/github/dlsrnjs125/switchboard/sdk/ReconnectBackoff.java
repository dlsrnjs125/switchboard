package io.github.dlsrnjs125.switchboard.sdk;

import java.time.Duration;
import java.util.function.DoubleSupplier;

final class ReconnectBackoff {
    private final long initialMillis;
    private final long maximumMillis;
    private final double jitter;
    private final DoubleSupplier random;
    private int attempt;

    ReconnectBackoff(
            Duration initial, Duration maximum, double jitter, DoubleSupplier random) {
        this.initialMillis = initial.toMillis();
        this.maximumMillis = maximum.toMillis();
        this.jitter = jitter;
        this.random = random;
    }

    synchronized Duration nextDelay() {
        int exponent = Math.min(attempt++, 30);
        long delay = initialMillis;
        for (int index = 0; index < exponent && delay < maximumMillis; index++) {
            delay = delay > maximumMillis / 2 ? maximumMillis : Math.min(maximumMillis, delay * 2);
        }
        double factor = 1 - jitter + (2 * jitter * random.getAsDouble());
        return Duration.ofMillis(Math.max(1, (long) (delay * factor)));
    }

    synchronized void reset() {
        attempt = 0;
    }
}
