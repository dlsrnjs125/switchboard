package io.github.dlsrnjs125.switchboard.controlplane.infrastructure;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public final class UuidV7Generator {
    private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long RANDOM_A_MASK = 0x0FFFL;
    private static final long RANDOM_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final SecureRandom random;
    private final AtomicLong lastTimestampAndSequence = new AtomicLong(-1L);

    public UuidV7Generator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long millisAndSequence = lastTimestampAndSequence.updateAndGet(previous -> {
            long now = clock.millis() & TIMESTAMP_MASK;
            long previousMillis = previous < 0 ? -1 : previous >>> 12;
            long sequence = previousMillis == now ? (previous + 1) & RANDOM_A_MASK : random.nextLong() & RANDOM_A_MASK;
            return (now << 12) | sequence;
        });

        long millis = millisAndSequence >>> 12;
        long sequence = millisAndSequence & RANDOM_A_MASK;
        long mostSignificant = (millis << 16) | 0x7000L | sequence;
        long leastSignificant = 0x8000_0000_0000_0000L | (random.nextLong() & RANDOM_B_MASK);
        return new UUID(mostSignificant, leastSignificant);
    }
}
