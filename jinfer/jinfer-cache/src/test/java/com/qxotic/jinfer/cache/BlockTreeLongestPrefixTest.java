package com.qxotic.jinfer.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.qxotic.jinfer.CheckpointCodec;
import com.qxotic.jinfer.ContentKey;
import com.qxotic.jinfer.cache.BlockTreeEvictionTest.FakeState;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The longest-prefix law under budget pressure: a lookup lands on the LONGEST surviving chain for
 * its prompt - never on a shorter sibling because it is fresher, and never past a hole, because
 * eviction takes leaves only and every surviving chain stays contiguous. Blob sizes are exactly 8
 * bytes per position (no residue), so the budget arithmetic below is exact.
 */
public final class BlockTreeLongestPrefixTest {

    /** Rows only: a block of n positions is n * 8 bytes, which makes the budget math readable. */
    static final class FlatCodec extends CheckpointCodec<FakeState> {
        @Override
        protected long sizeOf(int positions) {
            return positions * 8L;
        }

        @Override
        protected void transfer(
                FakeState state, int from, int to, MemorySegment memory, boolean capture) {}
    }

    private static long[] fp(int n) {
        long[] fp = new long[n];
        for (int i = 0; i < n; i++) fp[i] = 100 + i;
        return fp;
    }

    /** Resumes {@code prompt[0..len)} into a fresh state and returns where it landed. */
    private static int landing(BlockTree<FakeState> tree, long[] prompt, int len) {
        FakeState s = new FakeState();
        tree.resume(prompt, len, s);
        return s.position();
    }

    /** Resumes, then ingests to {@code to} and commits the span as one block; returns the tip. */
    private static BlockTree<FakeState>.Block extend(
            BlockTree<FakeState> tree, long[] prompt, int to) {
        FakeState s = new FakeState();
        BlockTree<FakeState>.Block tip = tree.resume(prompt, to, s);
        int from = s.position();
        s.resumeAt(to);
        return tree.commit(tip, prompt, from, to - from, s);
    }

    @Test
    void theLongestSurvivingPrefixWinsAcrossEvictions() {
        // budget = exactly one 12-position chain
        BlockTree<FakeState> tree =
                new BlockTree<>(
                        new FlatCodec(),
                        CacheStore.inMemory(),
                        12 * 8,
                        ContentKey.sha256(new byte[] {1}));
        long[] a = fp(12);

        // chain A: [0,4) [4,8) [8,12), 96 bytes, the whole budget
        FakeState w = new FakeState();
        BlockTree<FakeState>.Block tip = tree.resume(new long[0], 0, w);
        for (int to = 4; to <= 12; to += 4) {
            w.resumeAt(to);
            tip = tree.commit(tip, a, to - 4, 4, w);
        }
        assertEquals(12, landing(tree, a, 12), "the full chain serves before any pressure");

        // prompt B = A[0..6): lands on [0,4) (the 8-block overshoots a 6-cap) and commits [4,6)
        // as a SIBLING of [4,8). 16 bytes over budget: the LRU leaf [8,12) is evicted (80 bytes
        // used after), leaving chain A as [0,4) [4,8) - contiguous, just shorter.
        long[] b = Arrays.copyOf(a, 6);
        extend(tree, b, 6);
        assertEquals(1, tree.sample().evictions(), "one leaf evicted to admit [4,6)");
        assertEquals(3, tree.sample().blocks(), "[0,4) [4,8) [4,6)");

        // THE LAW: both siblings match prompt A's positions 4.., and [4,6) is the fresher one -
        // the longer span wins, and the lookup lands on the surviving longer prefix, 8, not on
        // the fresher 6 and not on the pre-eviction 12
        assertEquals(8, landing(tree, a, 12), "longest surviving prefix, not the fresher sibling");
        assertEquals(6, landing(tree, b, 6), "the short prompt still finds its own exact block");

        // more pressure: prompt C shares A[0..8) and adds 4 positions (32 bytes: 80 used + 32 is
        // over budget). Its lookup touches [4,8), so the LRU leaf is now [4,6): evicted. Chains
        // stay contiguous.
        long[] c = Arrays.copyOf(a, 12);
        for (int i = 8; i < 12; i++) c[i] = 700 + i;
        extend(tree, c, 12);
        assertEquals(2, tree.sample().evictions());
        assertEquals(3, tree.sample().blocks(), "[0,4) [4,8) [8,12)'");
        assertEquals(12, landing(tree, c, 12), "the new chain serves whole");
        assertEquals(8, landing(tree, a, 12), "A still resumes at its longest surviving prefix");
        // B's own block is gone and the longer [4,8) cannot serve a 6-cap: back to the shared 4.
        // Shorter than before, but NEVER past a hole and never a wrong block.
        assertEquals(4, landing(tree, b, 6), "a lookup falls back to the surviving shorter prefix");
    }

    @Test
    void evictionNeverLeavesAHoleInsideAChain() {
        // many diverging tails under a budget for ~two of them: whatever survives, every prompt
        // resumes at a position that is the end of a CONTIGUOUS run of its own blocks - a lookup
        // that landed past a hole would restore rows the chain no longer holds
        BlockTree<FakeState> tree =
                new BlockTree<>(
                        new FlatCodec(),
                        CacheStore.inMemory(),
                        40 * 8,
                        ContentKey.sha256(new byte[] {2}));
        long[] shared = fp(8);
        long[][] prompts = new long[6][];
        for (int p = 0; p < prompts.length; p++) {
            prompts[p] = Arrays.copyOf(shared, 24);
            for (int i = 8; i < 24; i++) prompts[p][i] = 1000L * (p + 1) + i;
        }
        for (int round = 0; round < 4; round++) {
            for (long[] prompt : prompts) {
                // commit in three 8-position blocks, each chained on wherever the lookup landed
                for (int to = 8; to <= 24; to += 8) {
                    if (landing(tree, prompt, to) < to) extend(tree, prompt, to);
                }
            }
        }
        for (long[] prompt : prompts) {
            int landed = landing(tree, prompt, 24);
            assertEquals(0, landed % 8, "landed on a block boundary: " + landed);
            // every shorter cap lands at min(cap, landed) rounded down to a boundary: the chain
            // below the landing is intact, so a shorter lookup never finds a hole
            for (int cap = 8; cap <= 24; cap += 8) {
                assertEquals(
                        Math.min(cap, landed),
                        landing(tree, prompt, cap),
                        "contiguous below " + landed + " at cap " + cap);
            }
        }
    }
}
