package com.qxotic.jinfer.cache;

import static com.qxotic.jinfer.cache.PromptCacheTest.SEED;
import static com.qxotic.jinfer.cache.PromptCacheTest.fine;
import static com.qxotic.jinfer.cache.PromptCacheTest.generate;
import static com.qxotic.jinfer.cache.PromptCacheTest.onCatalog;
import static com.qxotic.jinfer.cache.PromptCacheTest.options;
import static com.qxotic.jinfer.cache.PromptCacheTest.turns;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.jinfer.Batch;
import com.qxotic.jinfer.ContentKey;
import com.qxotic.jinfer.cache.PromptCacheTest.FakeModel;
import com.qxotic.jinfer.cache.PromptCacheTest.FakeState;
import com.qxotic.jinfer.cache.PromptCacheTest.Served;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prompt compiler across a restart, driven through {@link PromptCache} exactly as the engine
 * drives it (the package fixture from {@link PromptCacheTest}): a catalog saved by one process and
 * mounted by the next recomputes EXACTLY the positions the cache does not hold - the one-short law
 * included - and a damaged or foreign artifact is refused with the one stable error, never served
 * as a torn prefix.
 *
 * <p>Recompute is counted where it happens: {@link FakeState#ingested} records every token id the
 * model actually ingested into a state, so {@code ingested.size()} IS the prefill the cache failed
 * to save.
 */
public final class PromptCacheRestartTest {

    @TempDir Path dir;

    /** The stream one boot leaves behind: a 10-token prompt and a two-token reply, 12 positions. */
    private static final int[] PROMPT = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    private static final int[] REPLY = {20, 21};
    private static final int CACHED = PROMPT.length + REPLY.length;

    /** What a serve ingested into its state next to what it was told it reused. */
    private record Recompute(PromptCache.Tier tier, int restored, int ingested) {}

    private static Recompute serveCounting(PromptCache<FakeState> cache, List<Batch> prompt) {
        return cache.serve(
                prompt,
                (state, serving) ->
                        new Recompute(serving.tier(), serving.restored(), state.ingested.size()));
    }

    /** One boot's traffic, saved: a catalog holding the 12-position stream. */
    private Path savedCatalog(FakeModel model) {
        Path catalog = dir.resolve("catalog.jkvf");
        try (var first = onCatalog(model, catalog, false)) {
            Served cold = generate(first, turns(PROMPT), REPLY);
            assertEquals(PromptCache.Tier.FRESH, cold.tier(), "the first boot computes it all");
            first.save();
        }
        return catalog;
    }

    // ---- (a) recompute accounting across save + load ------------------------------------------

    @Test
    void aFreshInstanceRecomputesOnlyWhatTheCatalogDoesNotHold() {
        FakeModel model = fine();
        Path catalog = savedCatalog(model);

        try (var second = onCatalog(model, catalog, true)) {
            // the next turn: the whole cached stream plus three new tokens. Every cached position
            // restores, and the model ingests exactly the delta - total minus matched.
            int[] turn2 = {20, 21, 30, 31, 32};
            int total = PROMPT.length + turn2.length;
            Recompute extended = serveCounting(second, turns(PROMPT, turn2));
            assertEquals(PromptCache.Tier.BLOCKS, extended.tier(), "served from yesterday's file");
            assertEquals(CACHED, extended.restored(), "every position on disk is reused");
            assertEquals(
                    total - extended.restored(),
                    extended.ingested(),
                    "recompute = requested - matched");
            assertEquals(3, extended.ingested());

            // the identical stream again: the cache holds all 12 positions, yet the one-short law
            // keeps the final position out of the restore so the logits are fresh - recompute is
            // total - cached + 1, exactly one position
            Recompute identical = serveCounting(second, turns(PROMPT, REPLY));
            assertEquals(PromptCache.Tier.BLOCKS, identical.tier());
            assertEquals(CACHED - 1, identical.restored(), "one short");
            assertEquals(
                    1,
                    identical.ingested(),
                    "the re-ingested final position is the whole recompute");
            assertEquals(CACHED, identical.restored() + identical.ingested());
        }
    }

    // ---- (b) damaged artifacts: refused with the named error, never a torn resume -------------

    @Test
    void aTruncatedExportIsRefusedWithTheNamedError() throws Exception {
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        // an export is ONE generation: truncating it leaves no complete commit to fall back to
        Path export = dir.resolve("export.jkvf");
        try (var cache = onCatalog(model, catalog, true)) {
            cache.export(export);
        }
        long size = Files.size(export);

        Path insideHeader = copy(export, "truncated-header.jkvf", 100);
        assertRefused(model, insideHeader, "truncated header");

        Path insideIndex = copy(export, "truncated-index.jkvf", size - 1);
        assertRefused(model, insideIndex, "no valid commit slot");
    }

    @Test
    void aTruncatedCatalogRollsBackToItsLastCompleteGenerationAndServesCold() throws Exception {
        // an accumulating catalog carries two commit slots: a cut inside the newest generation
        // falls back to the previous one (here: the EMPTY birth generation). That is the torn-
        // append contract - and the fallback serves a cold prefill, never part of the cut blocks
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        Path cut = copy(catalog, "torn-append.jkvf", Files.size(catalog) - 1);
        try (var cache = onCatalog(model, cut, true)) {
            int total = PROMPT.length + REPLY.length;
            Recompute served = serveCounting(cache, turns(PROMPT, REPLY));
            assertEquals(PromptCache.Tier.FRESH, served.tier(), "the torn generation is gone");
            assertEquals(0, served.restored());
            assertEquals(total, served.ingested(), "everything recomputed, nothing torn");
        }
    }

    @Test
    void aFlippedHeaderOrIndexByteIsRefusedWithTheNamedError() throws Exception {
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        Path export = dir.resolve("export.jkvf");
        try (var cache = onCatalog(model, catalog, true)) {
            cache.export(export);
        }
        // header page: inside the stored identity digest, which the header CRC covers
        Path header = flip(export, "flipped-header.jkvf", FrozenBlocks.HEADER_DIGEST_OFFSET + 4);
        assertRefused(model, header, "header checksum mismatch");
        // the index: its CRC is in the commit slot, so the slot stops validating
        FrozenBlocks pristine = FrozenBlocks.open(export, SEED);
        long indexOffset = indexOffset(export);
        Path index =
                flip(
                        export,
                        "flipped-index.jkvf",
                        indexOffset + FrozenBlocks.ENTRY_PARENT_KEY_OFFSET + 8); // entry 0
        assertRefused(model, index, "no valid commit slot");
        assertEquals(4, pristine.blockCount(), "the untouched export still mounts");
    }

    @Test
    void aFlippedKvByteDegradesToAMissAtThatBlockNeverAWrongRestore() throws Exception {
        // blob CRCs verify lazily at first restore: the damaged block and everything chained on
        // it are discarded; the verified prefix below it survives, and the model re-ingests the
        // rest. Position accounting stays exact either way - the state is never torn.
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        Path export = dir.resolve("export.jkvf");
        try (var cache = onCatalog(model, catalog, true)) {
            cache.export(export);
        }
        List<FrozenBlocks.Entry> entries = FrozenBlocks.open(export, SEED).entries();
        int total = PROMPT.length + REPLY.length;

        // the FIRST block of the chain: nothing below it, so the whole prompt recomputes
        Path first = flip(export, "flipped-first-blob.jkvf", entries.get(0).offset());
        try (var cache = onCatalog(model, first, true)) {
            Recompute served = serveCounting(cache, turns(PROMPT, REPLY));
            assertEquals(PromptCache.Tier.FRESH, served.tier());
            assertEquals(0, served.restored(), "a corrupt first block is a cold start");
            assertEquals(total, served.ingested(), "every position recomputed");
            assertTrue(cache.sample().blockDiscards() > 0, "the corrupt chain was discarded");
        }

        // a LATER block: the verified prefix [0, from) restores; the rest recomputes
        FrozenBlocks.Entry later = entries.get(1);
        Path second = flip(export, "flipped-later-blob.jkvf", later.offset());
        try (var cache = onCatalog(model, second, true)) {
            Recompute served = serveCounting(cache, turns(PROMPT, REPLY));
            assertEquals(later.from(), served.restored(), "restored up to the damaged block");
            assertEquals(total - later.from(), served.ingested(), "the rest recomputed");
            assertEquals(total, served.restored() + served.ingested(), "positions add up");
        }
    }

    // ---- (c) another model's catalog never matches -------------------------------------------

    @Test
    void aCatalogBuiltUnderAnotherIdentityIsRefusedAndLeftIntact() throws Exception {
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        byte[] before = Files.readAllBytes(catalog);
        ContentKey other = ContentKey.sha256(new byte[] {43});

        // read-only mount: refused by name
        IllegalStateException ro =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PromptCache.of(
                                        model,
                                        other,
                                        options(0, 1 << 20).withCatalog(catalog, true)));
        assertTrue(ro.getMessage().contains("different cache identity"), ro.getMessage());

        // read-write mount: refused the same way, and the file is NOT recreated under the new
        // identity - that would destroy the old model's catalog
        IllegalStateException rw =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PromptCache.of(
                                        model,
                                        other,
                                        options(0, 1 << 20).withCatalog(catalog, false)));
        assertTrue(rw.getMessage().contains("different cache identity"), rw.getMessage());
        assertArrayEquals(before, Files.readAllBytes(catalog), "the refused catalog is untouched");

        // and the right identity still serves it
        try (var same = onCatalog(model, catalog, true)) {
            assertEquals(PromptCache.Tier.BLOCKS, generate(same, turns(PROMPT, REPLY)).tier());
        }
    }

    @Test
    void aMountOpenedUnderOneSeedCannotGraftIntoATreeRootedInAnother() throws Exception {
        // the chain root hangs from the seed: an artifact that opened under its own seed still
        // refuses to graft under a tree keyed by a different one (no block can find its parent)
        FakeModel model = fine();
        Path catalog = savedCatalog(model);
        FrozenBlocks mounted = FrozenBlocks.open(catalog, SEED);
        IllegalStateException refused =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new BlockTree<>(
                                        model.codec,
                                        CacheStore.inMemory(),
                                        1 << 20,
                                        ContentKey.sha256(new byte[] {44}),
                                        mounted));
        assertTrue(refused.getMessage().contains("not rooted in this cache"), refused.getMessage());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void assertRefused(FakeModel model, Path artifact, String reason) {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PromptCache.of(
                                        model,
                                        SEED,
                                        options(0, 1 << 20).withCatalog(artifact, true)));
        assertTrue(
                e.getMessage().contains("not a valid frozen prompt cache"),
                "the one stable error, got: " + e.getMessage());
        assertTrue(e.getMessage().contains(reason), "names the reason, got: " + e.getMessage());
    }

    /** The first {@code length} bytes of {@code source}, as a new file. */
    private Path copy(Path source, String name, long length) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        Path out = dir.resolve(name);
        Files.write(out, Arrays.copyOf(bytes, Math.toIntExact(length)));
        return out;
    }

    /** {@code source} with one byte inverted at {@code offset}, as a new file. */
    private Path flip(Path source, String name, long offset) throws IOException {
        Path out = dir.resolve(name);
        Files.copy(source, out);
        try (FileChannel ch =
                FileChannel.open(out, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, offset);
            one.flip();
            one.put(0, (byte) (one.get(0) ^ 0xFF));
            ch.write(one, offset);
        }
        return out;
    }

    /** The serving commit slot's index offset (see the artifact layout in FrozenBlocks). */
    private static long indexOffset(Path artifact) throws IOException {
        ByteBuffer slot = ByteBuffer.allocate(FrozenBlocks.SLOT_BYTES);
        try (FileChannel ch = FileChannel.open(artifact)) {
            ch.read(slot, FrozenBlocks.SLOT_A_OFFSET);
        }
        return slot.order(ByteOrder.LITTLE_ENDIAN).getLong(FrozenBlocks.SLOT_INDEX_OFFSET_FIELD);
    }
}
