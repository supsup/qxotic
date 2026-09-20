package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Measures whether {@code countTokens} actually costs less than {@code encode}.
 *
 * <p>Motivation (qxoticai/qxotic#3): toknroll's count path allocates an {@code IntSequence.Builder}
 * and runs the full encode, then discards the list. jtokkit instead threads a {@code keepEncodings}
 * flag through the identical merge loop and skips one final materialization pass. The open question
 * is how much that is actually worth, because the merge loop -- which determines the token count --
 * must run either way.
 *
 * <p>Run: {@code java -cp ... CountVsEncodeBenchmark <tokenizer.json> <corpus.txt>}
 */
public final class CountVsEncodeBenchmark {

    private static final int WARMUP_ITERS = 12;
    private static final int MEASURED_ITERS = 15;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CountVsEncodeBenchmark <tokenizer.json> <corpus.txt>");
            System.exit(2);
        }
        Tokenizer tok = HuggingFaceTokenizerLoader.fromLocal(Path.of(args[0]));
        String corpus = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);

        System.out.printf(
                "corpus: %,d chars | %,d words%n", corpus.length(), corpus.split("\\s+").length);

        // CORRECTNESS CONTROL FIRST. A faster count that disagrees with encode is worthless, and
        // if these ever diverge every timing below is measuring two different computations.
        int encoded = tok.encode(corpus).length();
        int counted = tok.countTokens(corpus);
        if (encoded != counted) {
            throw new IllegalStateException(
                    "count/encode disagree: encode=" + encoded + " count=" + counted);
        }
        System.out.printf("tokens: %,d (encode == countTokens, verified)%n%n", encoded);

        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink(tok.encode(corpus).length());
            sink(tok.countTokens(corpus));
        }

        long[] encodeNs = new long[MEASURED_ITERS];
        long[] countNs = new long[MEASURED_ITERS];
        // Interleaved, so drift in machine state hits both arms equally rather than whichever
        // one happens to run second.
        for (int i = 0; i < MEASURED_ITERS; i++) {
            long t0 = System.nanoTime();
            sink(tok.encode(corpus).length());
            encodeNs[i] = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            sink(tok.countTokens(corpus));
            countNs[i] = System.nanoTime() - t1;
        }

        report("encode(corpus)", encodeNs, encoded);
        report("countTokens(corpus)", countNs, encoded);

        double eMed = median(encodeNs);
        double cMed = median(countNs);
        System.out.printf(
                "%ncount vs encode: %.1f%% of encode time (median). Saving: %.1f%%%n",
                100.0 * cMed / eMed, 100.0 * (1.0 - cMed / eMed));
    }

    private static void report(String label, long[] ns, int tokens) {
        long[] s = ns.clone();
        Arrays.sort(s);
        double medMs = s[s.length / 2] / 1e6;
        double minMs = s[0] / 1e6;
        System.out.printf(
                "%-22s median %7.2f ms | min %7.2f ms | %6.2f Mtok/s (median)%n",
                label, medMs, minMs, tokens / (medMs * 1000.0));
    }

    private static double median(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static int blackhole;

    private static void sink(int v) {
        blackhole += v;
    }
}
