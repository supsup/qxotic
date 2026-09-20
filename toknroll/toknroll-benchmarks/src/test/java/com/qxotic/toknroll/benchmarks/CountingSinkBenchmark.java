package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.IntSequence;
import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Isolates how much of {@code countTokens} is the token-list materialization, by running the real
 * encode path into a sink that discards every append.
 *
 * <p>This is the toknroll analogue of jtokkit's {@code keepEncodings=false}: same merge loop, no
 * stored output. {@code IntSequence.Builder} is an interface, so this needs no change to the 16
 * {@code out.add} sites in TiktokenModel -- which is also why it is a fair upper bound on what such
 * a change could buy. Anything this does NOT save, threading a flag would not save either.
 */
public final class CountingSinkBenchmark {

    private static final int WARMUP_ITERS = 12;
    private static final int MEASURED_ITERS = 15;

    /** Counts appends and stores nothing. */
    static final class CountingSink implements IntSequence.Builder {
        private int n;

        @Override
        public int size() {
            return n;
        }

        @Override
        public void ensureCapacity(int minCapacity) {}

        @Override
        public IntSequence.Builder add(int value) {
            n++;
            return this;
        }

        @Override
        public IntSequence build() {
            throw new UnsupportedOperationException("counting sink stores nothing");
        }

        @Override
        public IntSequence snapshot() {
            throw new UnsupportedOperationException("counting sink stores nothing");
        }

        @Override
        public IntSequence asSequenceView() {
            throw new UnsupportedOperationException("counting sink stores nothing");
        }

        @Override
        public IntSequence.Builder addAll(IntSequence elems) {
            n += elems.length();
            return this;
        }

        void reset() {
            n = 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Tokenizer tok = HuggingFaceTokenizerLoader.fromLocal(Path.of(args[0]));
        String corpus = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);

        int expected = tok.countTokens(corpus);
        CountingSink sink = new CountingSink();
        tok.encodeInto(corpus, sink);
        if (sink.size() != expected) {
            throw new IllegalStateException(
                    "sink disagrees: sink=" + sink.size() + " countTokens=" + expected);
        }
        System.out.printf("tokens: %,d (sink == countTokens, verified)%n%n", expected);

        for (int i = 0; i < WARMUP_ITERS; i++) {
            sinkInt(tok.countTokens(corpus));
            sink.reset();
            tok.encodeInto(corpus, sink);
            sinkInt(sink.size());
        }

        long[] currentNs = new long[MEASURED_ITERS];
        long[] sinkNs = new long[MEASURED_ITERS];
        for (int i = 0; i < MEASURED_ITERS; i++) {
            long t0 = System.nanoTime();
            sinkInt(tok.countTokens(corpus));
            currentNs[i] = System.nanoTime() - t0;

            sink.reset();
            long t1 = System.nanoTime();
            tok.encodeInto(corpus, sink);
            sinkInt(sink.size());
            sinkNs[i] = System.nanoTime() - t1;
        }

        report("countTokens (current)", currentNs, expected);
        report("encode -> counting sink", sinkNs, expected);
        System.out.printf(
                "%nsink vs current countTokens: %.1f%% of the time. Saving: %.1f%%%n",
                100.0 * median(sinkNs) / median(currentNs),
                100.0 * (1.0 - median(sinkNs) / median(currentNs)));
    }

    private static void report(String label, long[] ns, int tokens) {
        long[] s = ns.clone();
        Arrays.sort(s);
        double medMs = s[s.length / 2] / 1e6;
        System.out.printf(
                "%-26s median %7.2f ms | min %7.2f ms | %6.2f Mtok/s%n",
                label, medMs, s[0] / 1e6, tokens / (medMs * 1000.0));
    }

    private static double median(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static int blackhole;

    private static void sinkInt(int v) {
        blackhole += v;
    }
}
