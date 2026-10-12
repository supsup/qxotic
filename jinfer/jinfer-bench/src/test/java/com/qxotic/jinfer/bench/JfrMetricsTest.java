package com.qxotic.jinfer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.jinfer.telemetry.DecodeEvent;
import com.qxotic.jinfer.telemetry.InferenceEvent;
import com.qxotic.jinfer.telemetry.ModelLoadEvent;
import com.qxotic.jinfer.telemetry.PrefillEvent;
import com.qxotic.jinfer.telemetry.TokenizeEvent;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reader against recordings this test produces itself: jinfer's events hand-filled with known
 * numbers, the JDK's real.
 */
class JfrMetricsTest {

    @Test
    void reducesARecordingToTheTable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("synthetic.jfr");
        try (Recording recording = new Recording()) {
            for (String name :
                    new String[] {
                        "jinfer.ModelLoad",
                        "jinfer.Tokenize",
                        "jinfer.Prefill",
                        "jinfer.Decode",
                        "jinfer.Inference",
                        "jdk.GarbageCollection"
                    }) recording.enable(name);
            recording.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofMillis(5));
            recording.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(5));
            recording.start();

            ModelLoadEvent load = new ModelLoadEvent();
            load.begin();
            spin(Duration.ofMillis(2));
            load.end();
            load.model = "synthetic.gguf";
            load.architecture = "test";
            load.maxContextLength = 4096;
            load.weightsBytes = 3L << 20;
            load.mapped = true;
            load.commit();

            TokenizeEvent tokenize = new TokenizeEvent();
            tokenize.begin();
            spin(Duration.ofMillis(1));
            tokenize.end();
            tokenize.chars = 100;
            tokenize.tokens = 25;
            tokenize.commit();

            for (int chunk = 0; chunk < 2; chunk++) {
                PrefillEvent prefill = new PrefillEvent();
                prefill.begin();
                spin(Duration.ofMillis(1));
                prefill.end();
                prefill.tokens = 256;
                prefill.commit();
            }

            for (int step = 0; step < 10; step++) {
                DecodeEvent decode = new DecodeEvent();
                decode.begin();
                spin(Duration.ofMillis(1 + step % 3));
                decode.end();
                decode.commit();
            }

            InferenceEvent inference =
                    InferenceEvent.started(
                            "synthetic.gguf", InferenceEvent.CHAT, InferenceEvent.TEXT);
            inference.inputTokens = 512;
            inference.outputTokens = 10;
            inference.timeToFirstToken = 7_000_000L;
            inference.prefillTime = 2_000_000L;
            inference.decodeTime = 20_000_000L;
            inference.finishReason = "length";
            inference.end();
            inference.commit();

            Thread.sleep(15); // periodic samples before the allocation...
            byte[][] garbage = new byte[16][];
            for (int i = 0; i < garbage.length; i++) garbage[i] = new byte[1 << 20];
            assertEquals(16, garbage.length);
            System.gc();
            Thread.sleep(15); // ...and after it

            recording.stop();
            recording.dump(file);
        }

        JfrMetrics.Report report = JfrMetrics.read(file);
        assertEquals(1, report.loads);
        assertEquals(3L << 20, report.weightsBytes);
        assertEquals("test", report.architecture);
        assertTrue(report.loadNanos >= 2_000_000L, "a 2 ms load: " + report.loadNanos);
        assertEquals(1, report.tokenizations);
        assertEquals(100, report.tokenizeChars);
        assertEquals(25, report.tokenizeTokens);
        assertEquals(2, report.prefillChunks);
        assertEquals(512, report.prefillTokens);
        assertTrue(report.prefillTokensPerSecond() > 0);
        assertEquals(10, report.decodeSteps());
        assertTrue(report.decodeStepNanos(0.5) >= 1_000_000L, "no step took under 1 ms");
        assertTrue(report.decodeStepNanos(0.5) <= report.decodeStepNanos(0.95));
        assertTrue(report.decodeStepNanos(0.95) <= report.decodeStepNanos(1.0));
        double tps = report.decodeTokensPerSecond();
        assertTrue(tps > 0 && tps < 1000, "ten steps of 1-3 ms: " + tps);
        assertEquals(1, report.inferences);
        assertEquals(1, report.firstTokenNanos.size());
        assertTrue(report.gcs >= 1, "System.gc() under a recording emits jdk.GarbageCollection");
        assertTrue(report.allocatedBytes() >= 0);
        assertTrue(report.span().toMillis() >= 30, "two 15 ms sleeps: " + report.span());

        String table = report.render();
        for (String row :
                new String[] {
                    "| recording |",
                    "| model load |",
                    "| tokenizer |",
                    "| prefill |",
                    "| decode |",
                    "| time to first token |",
                    "| gc |",
                    "| allocation |",
                    "| cpu |"
                }) assertTrue(table.contains(row), row + " missing from:\n" + table);
        assertTrue(table.contains("512 tokens in 2 chunks"), table);
        assertTrue(table.contains("| decode | 10 tokens"), table);
        assertTrue(table.contains("3.0 MB weights, test, synthetic.gguf"), table);
        assertTrue(table.contains("100 chars -> 25 tokens"), table);
    }

    @Test
    void perRequestTimingsServeWhenThePerTokenEventsAreOff(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("aggregate.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jinfer.Inference");
            recording.start();
            InferenceEvent inference =
                    InferenceEvent.started("m.gguf", InferenceEvent.CHAT, InferenceEvent.TEXT);
            inference.inputTokens = 120;
            inference.cachedTokens = 20;
            inference.outputTokens = 20;
            inference.prefillTime = 50_000_000L;
            inference.decodeTime = 200_000_000L;
            inference.end();
            inference.commit();
            recording.stop();
            recording.dump(file);
        }

        JfrMetrics.Report report = JfrMetrics.read(file);
        assertEquals(0, report.decodeSteps());
        assertEquals(0, report.prefillChunks);
        assertEquals(100.0, report.decodeTokensPerSecond(), 1e-6, "20 tokens in 200 ms");
        assertEquals(2000.0, report.prefillTokensPerSecond(), 1e-6, "100 fresh tokens in 50 ms");
        String table = report.render();
        assertTrue(table.contains("per-request timings"), table);
        assertTrue(table.contains("| decode | 20 tokens, 100.0 t/s"), table);
        assertTrue(table.contains("| prefill | 100 tokens in 0 chunks, 2000.0 t/s"), table);
    }

    @Test
    void absentEventsReadAsNotAvailableNotZero(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(file);
        }

        JfrMetrics.Report report = JfrMetrics.read(file);
        assertEquals(0, report.decodeSteps());
        assertEquals(0.0, report.decodeTokensPerSecond());
        String table = report.render();
        assertTrue(table.contains("| model load | n/a |"), table);
        assertTrue(table.contains("| tokenizer | n/a |"), table);
        assertTrue(table.contains("| prefill | n/a |"), table);
        assertTrue(table.contains("| decode | n/a |"), table);
        assertTrue(table.contains("| cpu | n/a |"), table);
    }

    @Test
    void unitsReadWithoutZeros() {
        assertEquals("1.50 s", JfrMetrics.seconds(1_500_000_000L));
        assertEquals("21.3 ms", JfrMetrics.seconds(21_300_000L));
        assertEquals("0.5 us", JfrMetrics.seconds(500L));
        assertEquals("1.25 GB", JfrMetrics.bytes(5L << 28));
        assertEquals("3.0 MB", JfrMetrics.bytes(3L << 20));
        assertEquals("512 B", JfrMetrics.bytes(512));
        assertEquals("245.8", JfrMetrics.rate(245.82));
        assertEquals("8551.4", JfrMetrics.rate(8551.38));
        assertEquals("85.5 k", JfrMetrics.rate(85_513.8));
        assertEquals("1.20 M", JfrMetrics.rate(1_200_000));
    }

    /** Busy-waits, so the event durations are real and never shorter than asked. */
    private static void spin(Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
}
