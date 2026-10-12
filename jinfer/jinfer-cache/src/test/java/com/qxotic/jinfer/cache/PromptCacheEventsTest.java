package com.qxotic.jinfer.cache;

import static com.qxotic.jinfer.cache.PromptCacheTest.cache;
import static com.qxotic.jinfer.cache.PromptCacheTest.fine;
import static com.qxotic.jinfer.cache.PromptCacheTest.generate;
import static com.qxotic.jinfer.cache.PromptCacheTest.onCatalog;
import static com.qxotic.jinfer.cache.PromptCacheTest.prompt;
import static com.qxotic.jinfer.cache.PromptCacheTest.turns;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.jinfer.cache.PromptCacheTest.FakeModel;
import com.qxotic.jinfer.cache.PromptCacheTest.Served;
import com.qxotic.jinfer.telemetry.PromptCacheLookupEvent;
import com.qxotic.jinfer.telemetry.PromptCacheStoreEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-request and per-write JFR events the cache emits, recorded through the JDK's own
 * consumer: a lookup reports the positions it reused (0 on a miss, the one-short count on a hit,
 * the retained length on a session continuation) and whether the restore came off a mounted
 * catalog; a store reports what it wrote and how big the artifact is afterwards.
 */
public final class PromptCacheEventsTest {

    @TempDir Path dir;

    private static final String LOOKUP = "jinfer.PromptCacheLookup";
    private static final String STORE = "jinfer.PromptCacheStore";

    @Test
    void aLookupReportsWhatItReusedAndFromWhichTier() throws Exception {
        List<RecordedEvent> events =
                named(
                        record(
                                LOOKUP,
                                () -> {
                                    try (var c = cache(fine(), 2, 1 << 20)) {
                                        // miss: nothing cached yet
                                        Served miss = generate(c, turns(new int[] {1, 2, 3}), 7, 8);
                                        assertEquals(PromptCache.Tier.FRESH, miss.tier());
                                        // session: the echo plus a new turn strictly extends
                                        // the hot state
                                        Served hot =
                                                generate(
                                                        c,
                                                        turns(
                                                                new int[] {1, 2, 3},
                                                                new int[] {7, 8, 4}));
                                        assertEquals(PromptCache.Tier.SESSION, hot.tier());
                                        // blocks: the original prompt again, one short of
                                        // its 3 positions
                                        Served blocks = generate(c, turns(new int[] {1, 2, 3}));
                                        assertEquals(PromptCache.Tier.BLOCKS, blocks.tier());
                                    }
                                }),
                        LOOKUP);
        assertEquals(3, events.size(), "one event per serve");

        RecordedEvent miss = events.get(0);
        assertEquals(3, miss.getInt("requestedTokens"));
        assertEquals(0, miss.getInt("matchedPrefixTokens"), "a miss reuses nothing");
        assertEquals("fresh", miss.getString("tier"));
        assertFalse(miss.getBoolean("resumedFromDisk"));
        assertEquals(0, miss.getInt("treeNodes"), "the tree was empty when the lookup ran");

        RecordedEvent session = events.get(1);
        assertEquals(6, session.getInt("requestedTokens"));
        assertEquals(5, session.getInt("matchedPrefixTokens"), "the whole retained stream");
        assertEquals("session", session.getString("tier"));
        assertFalse(session.getBoolean("resumedFromDisk"));

        RecordedEvent blocks = events.get(2);
        assertEquals(3, blocks.getInt("requestedTokens"));
        assertEquals(2, blocks.getInt("matchedPrefixTokens"), "one-short: 3 cached, 2 restored");
        assertEquals("blocks", blocks.getString("tier"));
        assertFalse(blocks.getBoolean("resumedFromDisk"), "RAM blocks, no catalog");
        assertTrue(blocks.getInt("treeNodes") > 0, "the tree had blocks by then");
        for (RecordedEvent event : events) {
            assertTrue(event.getDuration().toNanos() >= 0);
        }
    }

    @Test
    void aRestoreOffAMountedCatalogSaysSoAndAStoreReportsTheArtifact() throws Exception {
        FakeModel model = fine();
        Path catalog = dir.resolve("catalog.jkvf");
        Path export = dir.resolve("export.jkvf");
        List<RecordedEvent> events =
                record(
                        List.of(LOOKUP, STORE),
                        () -> {
                            try (var writer = onCatalog(model, catalog, false)) {
                                writer.define(prompt(1, 2, 3, 4, 5)); // no lookup: define is not
                                writer.save(); // a serve
                                writer.export(export);
                            }
                            try (var reader = onCatalog(model, catalog, true)) {
                                Served hit = generate(reader, prompt(1, 2, 3, 4, 5), 7);
                                assertEquals(PromptCache.Tier.BLOCKS, hit.tier());
                                assertEquals(4, hit.restored());
                            }
                        });
        List<RecordedEvent> stores = named(events, STORE);
        assertEquals(2, stores.size(), "one save, one export");
        RecordedEvent save = stores.get(0);
        assertEquals("save", save.getString("operation"));
        assertEquals(catalog.toString(), save.getString("file"));
        assertEquals(2, save.getInt("blocks"), "the defined prefix and its final single");
        assertEquals(Files.size(catalog), save.getLong("fileBytes"));
        RecordedEvent exported = stores.get(1);
        assertEquals("export", exported.getString("operation"));
        assertEquals(export.toString(), exported.getString("file"));
        assertEquals(2, exported.getInt("blocks"), "the whole tree");
        assertEquals(Files.size(export), exported.getLong("fileBytes"));

        List<RecordedEvent> lookups = named(events, LOOKUP);
        assertEquals(1, lookups.size(), "the reader served once");
        RecordedEvent fromDisk = lookups.get(0);
        assertEquals(5, fromDisk.getInt("requestedTokens"));
        assertEquals(4, fromDisk.getInt("matchedPrefixTokens"));
        assertEquals("blocks", fromDisk.getString("tier"));
        assertTrue(fromDisk.getBoolean("resumedFromDisk"), "every restored block was mounted");
        assertEquals(2, fromDisk.getInt("treeNodes"), "the mount's blocks count as nodes");
    }

    @Test
    void nothingIsEmittedWhenTheEventsAreOff() throws Exception {
        // both are on by default (a settings-less recording records them); switched off, the
        // serve and store paths pay only the isEnabled() check and emit nothing
        Path jfr = dir.resolve("off.jfr");
        try (Recording recording = new Recording()) {
            recording.disable(LOOKUP);
            recording.disable(STORE);
            recording.start();
            assertFalse(new PromptCacheLookupEvent().isEnabled());
            assertFalse(new PromptCacheStoreEvent().isEnabled());
            try (var c = onCatalog(fine(), dir.resolve("off.jkvf"), false)) {
                generate(c, prompt(1, 2, 3), 7);
                generate(c, prompt(1, 2, 3), 7);
                c.save();
            }
            recording.stop();
            recording.dump(jfr);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr);
        assertTrue(named(events, LOOKUP).isEmpty());
        assertTrue(named(events, STORE).isEmpty());
    }

    private List<RecordedEvent> record(String name, Runnable body) throws Exception {
        return record(List.of(name), body);
    }

    /** Runs {@code body} inside a recording of {@code names} and returns every recorded event. */
    private List<RecordedEvent> record(List<String> names, Runnable body) throws Exception {
        Path jfr = dir.resolve("events-" + System.nanoTime() + ".jfr");
        try (Recording recording = new Recording()) {
            for (String name : names) recording.enable(name).withThreshold(Duration.ZERO);
            recording.start();
            body.run();
            recording.stop();
            recording.dump(jfr);
        }
        return RecordingFile.readAllEvents(jfr);
    }

    /** The events called {@code name}, in the order they started. */
    private static List<RecordedEvent> named(List<RecordedEvent> events, String name) {
        return events.stream()
                .filter(e -> e.getEventType().getName().equals(name))
                .sorted(Comparator.comparing(RecordedEvent::getStartTime))
                .toList();
    }
}
