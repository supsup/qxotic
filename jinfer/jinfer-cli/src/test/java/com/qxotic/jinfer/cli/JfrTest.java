package com.qxotic.jinfer.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.qxotic.format.gguf.Builder;
import com.qxotic.format.gguf.GGUF;
import com.qxotic.jinfer.hub.ModelStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/** {@code --jfr} through {@code Main.run} and the real engine, over the weightless provider. */
@Isolated("Loads through the test provider, which records into statics")
class JfrTest {
    @TempDir Path dir;

    @BeforeEach
    @AfterEach
    void resetProvider() {
        CliModelProvider.reset();
    }

    @Test
    void instructRecordsLoadTokenizePrefillDecodeAndInference() throws Exception {
        Path model = dir.resolve("language.gguf");
        GGUF.write(
                Builder.newBuilder()
                        .putString("general.architecture", "cli_test_language")
                        .putString("test.failure", "")
                        .build(),
                model);
        Path file = dir.resolve("run.jfr");
        var capture = new CliFixtures.Capture("");
        String[] args = {
            "-m", model.toString(), "instruct", "A prompt.", "-n", "4", "--temp", "0",
            "--jfr", file.toString()
        };
        int status = Main.run(args, capture.io, ModelStore.of(dir.resolve("cache")));
        assertEquals(0, status, capture.err());
        assertEquals("xxxx\n", capture.out().replace("\r\n", "\n"));
        assertTrue(capture.err().contains("jfr: " + file), capture.err());
        assertTrue(Files.size(file) > 0, "the recording was written on return");

        Map<String, List<RecordedEvent>> events = jinferEvents(file);
        List<RecordedEvent> loads = events.getOrDefault("jinfer.ModelLoad", List.of());
        assertEquals(1, loads.size(), "one load, through Models.load");
        RecordedEvent load = loads.getFirst();
        assertEquals("language.gguf", load.getString("model"));
        assertEquals("cli_test_language", load.getString("architecture"));
        assertEquals(16384, load.getInt("maxContextLength"), "the test model's declared context");
        assertTrue(load.getBoolean("mapped"));
        assertTrue(load.getDuration().toNanos() > 0);

        List<RecordedEvent> tokenized = events.getOrDefault("jinfer.Tokenize", List.of());
        assertEquals(1, tokenized.size(), "one prompt encoded");
        int promptTokens = tokenized.getFirst().getInt("tokens");
        assertTrue(promptTokens > 0);
        assertEquals("A prompt.".length(), tokenized.getFirst().getInt("chars"));

        List<RecordedEvent> prefills = events.getOrDefault("jinfer.Prefill", List.of());
        assertFalse(prefills.isEmpty(), "the prompt was ingested in at least one chunk");
        int ingested = 0;
        for (RecordedEvent prefill : prefills) ingested += prefill.getInt("tokens");
        assertEquals(promptTokens, ingested, "every encoded position was prefilled");

        int decodes = events.getOrDefault("jinfer.Decode", List.of()).size();
        assertTrue(
                Math.abs(decodes - 4) <= 1,
                "--jfr switches Decode on: one event per generated token (+/- the stop): "
                        + decodes);
        assertEquals(1, events.getOrDefault("jinfer.Inference", List.of()).size());
    }

    @Test
    void jfrIsAModelOptionAndNamesItselfWhenMisplaced() {
        assertEquals(
                Path.of("run.jfr"),
                Options.parse("instruct", "-m", "m", "hi", "--jfr", "run.jfr").jfr);
        assertEquals(
                Path.of("srv.jfr"), Options.parse("--jfr", "srv.jfr", "server", "-m", "m").jfr);
        assertNull(Options.parse("instruct", "-m", "m", "hi").jfr, "off unless asked for");
        var misplaced =
                assertThrows(
                        Options.UsageException.class,
                        () -> Options.parse("list", "--jfr", "x.jfr"));
        assertTrue(misplaced.getMessage().contains("--jfr"), misplaced.getMessage());
        var missing =
                assertThrows(
                        Options.UsageException.class,
                        () -> Options.parse("instruct", "-m", "m", "hi", "--jfr"));
        assertTrue(missing.getMessage().contains("--jfr"), missing.getMessage());
    }

    @Test
    void settingsLayerJinferOverTheProfileWithDecodeOn() throws Exception {
        Map<String, String> settings = Flight.settings();
        assertEquals("true", settings.get("jdk.GarbageCollection#enabled"), "the JDK's profile");
        assertEquals("true", settings.get("jinfer.Inference#enabled"), "jinfer.jfc on top");
        assertEquals("true", settings.get("jinfer.Decode#enabled"), "the one override");
    }

    @Test
    void anUnwritableDestinationIsRefusedBeforeAnyModelLoads() throws Exception {
        Path model = dir.resolve("language.gguf");
        GGUF.write(
                Builder.newBuilder()
                        .putString("general.architecture", "cli_test_language")
                        .putString("test.failure", "")
                        .build(),
                model);
        Path file = dir.resolve("absent-directory").resolve("run.jfr");
        var capture = new CliFixtures.Capture("");
        String[] args = {"-m", model.toString(), "instruct", "hi", "--jfr", file.toString()};
        assertEquals(1, Main.run(args, capture.io, ModelStore.of(dir.resolve("cache"))));
        assertTrue(capture.err().contains("jinfer instruct: "), capture.err());
        assertEquals(0, CliModelProvider.languageLoads, "refused before the load");
    }

    /** Every {@code jinfer.*} event in the recording, by event name. */
    private static Map<String, List<RecordedEvent>> jinferEvents(Path file) throws Exception {
        Map<String, List<RecordedEvent>> byName = new HashMap<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String name = event.getEventType().getName();
                if (name.startsWith("jinfer."))
                    byName.computeIfAbsent(name, n -> new ArrayList<>()).add(event);
            }
        }
        return byName;
    }
}
