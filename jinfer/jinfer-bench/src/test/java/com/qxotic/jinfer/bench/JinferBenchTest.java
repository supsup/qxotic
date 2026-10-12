package com.qxotic.jinfer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.qxotic.jinfer.RuntimeFlags;
import org.junit.jupiter.api.Test;

class JinferBenchTest {

    /**
     * The rows report the engine's budget, read back, not the {@code -t} value echoed. Proven the
     * only way it can be inside one JVM: with {@code RuntimeFlags} already initialized, a request
     * that differs from its value must NOT come back - what comes back is the engine's.
     */
    @Test
    void rowThreadsAreTheEnginesValueNotTheRequestEchoed() {
        int engine = RuntimeFlags.THREADS; // initializes the budget for this JVM, if not yet
        String before = System.getProperty("jinfer.threads");
        try {
            assertEquals(engine, JinferBench.engineThreads(engine + 1));
            assertEquals(engine, JinferBench.engineThreads(0));
        } finally {
            if (before == null) System.clearProperty("jinfer.threads");
            else System.setProperty("jinfer.threads", before);
        }
    }
}
