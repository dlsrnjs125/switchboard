package io.github.dlsrnjs125.switchboard.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {
    @Test
    void growsExponentiallyCapsAndResets() {
        ReconnectBackoff backoff = new ReconnectBackoff(
                Duration.ofMillis(250), Duration.ofSeconds(1), 0.2, () -> 0.5);

        assertEquals(Duration.ofMillis(250), backoff.nextDelay());
        assertEquals(Duration.ofMillis(500), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(1), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(1), backoff.nextDelay());
        backoff.reset();
        assertEquals(Duration.ofMillis(250), backoff.nextDelay());
    }

    @Test
    void appliesConfiguredJitterBounds() {
        assertEquals(Duration.ofMillis(800), new ReconnectBackoff(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0.2, () -> 0).nextDelay());
        assertEquals(Duration.ofMillis(1200), new ReconnectBackoff(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0.2, () -> 1).nextDelay());
    }
}
