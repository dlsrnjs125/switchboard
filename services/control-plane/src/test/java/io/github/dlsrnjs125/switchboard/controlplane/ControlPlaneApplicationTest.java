package io.github.dlsrnjs125.switchboard.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ControlPlaneApplicationTest {
    @Test
    void exposesStableApplicationName() {
        assertEquals("switchboard-control-plane", ControlPlaneApplication.APPLICATION_NAME);
    }
}

