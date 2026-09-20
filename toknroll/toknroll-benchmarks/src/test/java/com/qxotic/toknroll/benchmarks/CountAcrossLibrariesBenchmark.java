package com.qxotic.toknroll.benchmarks;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Tests the counting claim on jtokkit's OWN code.
 *
 * <p>jtokkit's {@code countTokens} and {@code encode} are the same method with a {@code
 * keepEncodings} boolean; the flag skips a final pass that re-derives each token id with a byte[]
 * copy plus a hash lookup. This measures what that is actually worth, on jtokkit itself, so the
 * question does not rest on reading the source or on toknroll's structure.
 *
 * <p>r50k_base is GPT-2's encoding, matching the tokenizer used in the sibling benchmarks.
 */
public final class CountAcrossLibrariesBenchmark {

    private static final int WARMUP_ITERS = 12;
    private static final int MEASURED_ITERS = 15;

    public static void main(String[] args) throws Exception {
        String corpus = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Encoding enc = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.R50K_BASE);

        int encoded = enc.encode(corpus).size();
        int counted = enc.countTokens(corpus);
        if (encoded != counted) {
            throw new IllegalStateException("jtokkit count/encode disagree");
        }
        System.out.printf("jtokkit tokens: %,d (encode == countTokens, verified)%n%n", encoded);

        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink(enc.encode(corpus).size());
            sink(enc.countTokens(corpus));
        }

        long[] encodeNs = new long[MEASURED_ITERS];
        long[] countNs = new long[MEASURED_ITERS];
        for (int i = 0; i < MEASURED_ITERS; i++) {
            long t0 = System.nanoTime();
            sink(enc.encode(corpus).size());
            encodeNs[i] = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            sink(enc.countTokens(corpus));
            countNs[i] = System.nanoTime() - t1;
        }

        report("jtokkit encode", encodeNs, encoded);
        report("jtokkit countTokens", countNs, encoded);
        System.out.printf(
                "%njtokkit count vs its own encode: %.1f%% of the time. Saving: %.1f%%%n",
                100.0 * median(countNs) / median(encodeNs),
                100.0 * (1.0 - median(countNs) / median(encodeNs)));
    }

    private static void report(String label, long[] ns, int tokens) {
        long[] s = ns.clone();
        Arrays.sort(s);
        double medMs = s[s.length / 2] / 1e6;
        System.out.printf(
                "%-22s median %7.2f ms | min %7.2f ms | %6.2f Mtok/s%n",
                label, medMs, s[0] / 1e6, tokens / (medMs * 1000.0));
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
