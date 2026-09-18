package io.github.dlsrnjs125.switchboard.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DistributionApplicationTest {
    @Test
    void exposesStableApplicationName() {
        assertEquals("switchboard-distribution", DistributionApplication.APPLICATION_NAME);
    }
}

