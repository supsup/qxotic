package com.qxotic.jinfer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.jinfer.testkit.TestLanguageModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bench's own prompt path, over the weightless test model: it ingests at the raw model
 * boundary, below Generator and CachedSession, so it must emit jinfer.Prefill itself.
 */
class JinferBenchPrefillTest {

    @Test
    void benchPrefillEmitsOnePrefillEventPerChunk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bench-prefill.jfr");
        TestLanguageModel model = new TestLanguageModel();
        int[] prompt = new int[1300]; // 512 + 512 + 276 at a batch capacity of 512
        for (int i = 0; i < prompt.length; i++) prompt[i] = i % 256;
        try (Recording recording = new Recording();
                TestLanguageModel.State state = model.newState(2048, 512)) {
            recording.enable("jinfer.Prefill");
            recording.start();
            JinferBench.ingestPrefill(model, state, prompt);
            recording.stop();
            recording.dump(file);
            assertEquals(prompt.length, state.position());
        }

        List<Integer> chunks = new ArrayList<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            if (event.getEventType().getName().equals("jinfer.Prefill")) {
                chunks.add(event.getInt("tokens"));
            }
        }
        assertEquals(List.of(512, 512, 276), chunks);

        JfrMetrics.Report report = JfrMetrics.read(file);
        assertEquals(3, report.prefillChunks);
        assertEquals(1300, report.prefillTokens);
        String table = report.render();
        assertTrue(table.contains("| prefill | 1300 tokens in 3 chunks"), table);
    }
}
