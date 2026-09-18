package io.github.dlsrnjs125.switchboard.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SampleServiceApplicationTest {
    @Test
    void exposesStableApplicationName() {
        assertEquals("switchboard-sample-service", SampleServiceApplication.APPLICATION_NAME);
    }
}

