package com.qxotic.jinfer.cache;

import static com.qxotic.jinfer.cache.PromptCacheTest.cache;
import static com.qxotic.jinfer.cache.PromptCacheTest.fine;
import static com.qxotic.jinfer.cache.PromptCacheTest.generate;
import static com.qxotic.jinfer.cache.PromptCacheTest.turns;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.qxotic.jinfer.ContentKey;
import com.qxotic.jinfer.cache.PromptCacheTest.FakeState;
import com.qxotic.jinfer.cache.PromptCacheTest.Served;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrent lookups and what the design actually promises about them. A {@link BlockTree} is
 * single-threaded by contract (its digest scratch, chain scratch and counters belong to the one
 * generation thread, see its class doc), so the two shapes the engine really runs are proven here:
 *
 * <ul>
 *   <li>SHARED ARTIFACT - one mounted {@link FrozenBlocks} serving several trees at once (forked
 *       pipelines over one catalog): every thread resolves the same prefix to the same position and
 *       restores the same content, and the mount's own counters are never involved.
 *   <li>SERIALIZED TREE - one {@link PromptCache} driven from several threads under the engine's
 *       lock: every serve after the first is the same block hit with the same restored count.
 * </ul>
 */
public final class SharedCatalogConcurrencyTest {

    @TempDir Path dir;

    private static final int THREADS = 8;
    private static final int ROUNDS = 200;

    @Test
    void oneMountedArtifactServesManyTreesConsistently() throws Exception {
        ContentKey seed = ContentKey.sha256(new byte[] {21});
        BlockResumeTest.FakeCodec codec = new BlockResumeTest.FakeCodec();
        // two prompts sharing a 10-position prefix, frozen: [shared 10][A tail 6] / [B tail 6]
        long[] a = FrozenBlocksTest.fp(16, 100);
        long[] b = a.clone();
        for (int i = 10; i < 16; i++) b[i] = 900 + i;
        BlockTree<BlockResumeTest.FakeState> build =
                new BlockTree<>(codec, CacheStore.inMemory(), 1 << 20, seed);
        for (long[] prompt : new long[][] {a, b}) {
            BlockResumeTest.FakeState s = new BlockResumeTest.FakeState();
            BlockTree<BlockResumeTest.FakeState>.Block tip = build.resume(new long[0], 0, s);
            s.ingestTo(10);
            tip = build.commit(tip, prompt, 0, 10, s);
            s.ingestTo(16);
            build.commit(tip, prompt, 10, 6, s);
        }
        Path file = dir.resolve("shared.jkvf");
        build.freeze(file);
        FrozenBlocks mounted = FrozenBlocks.open(file, seed);
        long[] diverged = a.clone();
        diverged[13] ^= 1; // inside A's tail: lands on the shared prefix
        long[] cold = a.clone();
        cold[0] ^= 1; // inside the shared prefix: a miss

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    // each thread owns its tree and its states; the artifact
                                    // is the one thing they share
                                    BlockTree<BlockResumeTest.FakeState> tree =
                                            new BlockTree<>(
                                                    codec,
                                                    CacheStore.inMemory(),
                                                    1 << 20,
                                                    seed,
                                                    mounted);
                                    start.await();
                                    int hits = 0;
                                    for (int round = 0; round < ROUNDS; round++) {
                                        hits += expectResume(tree, a, 16);
                                        hits += expectResume(tree, b, 16);
                                        assertEquals(10, resumeAt(tree, diverged));
                                        assertEquals(0, resumeAt(tree, cold));
                                    }
                                    return hits;
                                }));
            }
            start.countDown();
            for (Future<Integer> outcome : outcomes) {
                assertEquals(2 * ROUNDS, outcome.get(), "every lookup on every thread hit");
            }
        }
        assertEquals(3, mounted.blockCount(), "the mount is immutable under the load");
    }

    /** Resumes the whole prompt and checks it landed at {@code expected} with exact content. */
    private static int expectResume(
            BlockTree<BlockResumeTest.FakeState> tree, long[] prompt, int expected) {
        BlockResumeTest.FakeState r = new BlockResumeTest.FakeState();
        tree.resume(prompt, prompt.length, r);
        assertEquals(expected, r.position());
        for (int p = 0; p < expected; p++) {
            assertEquals(BlockResumeTest.FakeState.rowAt(p), r.rows[p], "row " + p);
        }
        assertEquals(BlockResumeTest.FakeState.residueAt(expected), r.residue);
        return 1;
    }

    private static int resumeAt(BlockTree<BlockResumeTest.FakeState> tree, long[] prompt) {
        BlockResumeTest.FakeState r = new BlockResumeTest.FakeState();
        tree.resume(prompt, prompt.length, r);
        return r.position();
    }

    @Test
    void oneCacheUnderTheEngineLockServesEveryThreadTheSameHit() throws Exception {
        // the engine's shape: one PromptCache, one lock, many callers. The first serve of the
        // prompt computes it; every later one - whichever thread it lands on - is the same
        // one-short block hit, and the reply committed by the first serve is reused by all
        ReentrantLock lock = new ReentrantLock();
        int[] prompt = {1, 2, 3, 4, 5};
        try (PromptCache<FakeState> shared = cache(fine(), 0, 1 << 20)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Served>>> outcomes = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
                for (int t = 0; t < THREADS; t++) {
                    outcomes.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        List<Served> served = new ArrayList<>();
                                        for (int round = 0; round < ROUNDS; round++) {
                                            lock.lock();
                                            try {
                                                served.add(generate(shared, turns(prompt), 7));
                                            } finally {
                                                lock.unlock();
                                            }
                                        }
                                        return served;
                                    }));
                }
                start.countDown();
                int fresh = 0, hits = 0;
                for (Future<List<Served>> outcome : outcomes) {
                    for (Served s : outcome.get()) {
                        if (s.tier() == PromptCache.Tier.FRESH) {
                            fresh++;
                            assertEquals(0, s.restored());
                        } else {
                            hits++;
                            assertEquals(PromptCache.Tier.BLOCKS, s.tier());
                            assertEquals(prompt.length - 1, s.restored(), "one short, every time");
                        }
                    }
                }
                assertEquals(1, fresh, "exactly one thread computed the prompt");
                assertEquals(THREADS * ROUNDS - 1, hits, "everyone else reused it");
            }
            PromptCache.Sample sample = shared.sample();
            assertEquals(1, sample.blockMisses());
            assertEquals(THREADS * ROUNDS - 1, sample.blockHits());
            assertEquals(3, sample.blocks(), "prompt prefix, its final single, and the reply");
        }
    }
}
