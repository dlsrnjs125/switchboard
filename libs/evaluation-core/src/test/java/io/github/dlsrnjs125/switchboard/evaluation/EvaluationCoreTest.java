package io.github.dlsrnjs125.switchboard.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EvaluationCoreTest {
    @Test
    void remainsAStandaloneJavaModule() {
        assertEquals("evaluation-core", EvaluationCore.MODULE_NAME);
    }
}

