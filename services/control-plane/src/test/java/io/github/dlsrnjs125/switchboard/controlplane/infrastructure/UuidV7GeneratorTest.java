package io.github.dlsrnjs125.switchboard.controlplane.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {
    @Test
    void generatesDistinctRfc4122Version7IdentifiersAtTheSameInstant() {
        UuidV7Generator generator = new UuidV7Generator(
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC), new SecureRandom());

        UUID first = generator.next();
        UUID second = generator.next();

        assertEquals(7, first.version());
        assertEquals(2, first.variant());
        assertNotEquals(first, second);
        assertTrue(first.compareTo(second) < 0);
    }
}
