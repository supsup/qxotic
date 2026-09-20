package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.testkit.TiktokenFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Apples-to-apples: toknroll's TiktokenModel vs jtokkit, SAME encoding (r50k_base), same corpus.
 *
 * <p>Requires the r50k_base encoding on the test classpath, which this repo deliberately does not
 * vendor:
 *
 * <pre>
 *   curl -sL -o toknroll/toknroll-benchmarks/src/test/resources/tiktoken/r50k_base.tiktoken \
 *     https://openaipublic.blob.core.windows.net/encodings/r50k_base.tiktoken
 * </pre>
 *
 * <p>An earlier run compared jtokkit's built-in tiktoken path against toknroll loaded through the
 * HuggingFace pipeline (normalizer + splitter + metaspace) and produced a ~13x gap. That gap was
 * the PIPELINE, not the BPE core, and reporting it would have been a false finding. This routes
 * both libraries through their tiktoken paths so the comparison is about the merge loop.
 */
public final class ToknrollVsJtokkitCountBenchmark {

    private static final int WARMUP_ITERS = 12;
    private static final int MEASURED_ITERS = 15;

    public static void main(String[] args) throws Exception {
        String corpus = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Tokenizer tok = TiktokenFixtures.createTiktokenTokenizer("r50k_base");
        Tokenizer jt = TiktokenFixtures.createJtokkitTokenizer("r50k_base");

        int a = tok.countTokens(corpus);
        int b = jt.countTokens(corpus);
        System.out.printf("toknroll %,d tokens | jtokkit %,d tokens | agree: %b%n%n", a, b, a == b);

        for (int i = 0; i < WARMUP_ITERS; i++) {
            sink(tok.countTokens(corpus));
            sink(jt.countTokens(corpus));
            sink(tok.encode(corpus).length());
        }

        long[] tCount = new long[MEASURED_ITERS];
        long[] tEncode = new long[MEASURED_ITERS];
        long[] jCount = new long[MEASURED_ITERS];
        for (int i = 0; i < MEASURED_ITERS; i++) {
            long t0 = System.nanoTime();
            sink(tok.countTokens(corpus));
            tCount[i] = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            sink(tok.encode(corpus).length());
            tEncode[i] = System.nanoTime() - t1;

            long t2 = System.nanoTime();
            sink(jt.countTokens(corpus));
            jCount[i] = System.nanoTime() - t2;
        }

        report("toknroll countTokens", tCount, a);
        report("toknroll encode", tEncode, a);
        report("jtokkit countTokens", jCount, b);
        System.out.printf(
                "%ntoknroll count vs its own encode: %.1f%% (saving %.1f%%)%n",
                100.0 * median(tCount) / median(tEncode),
                100.0 * (1.0 - median(tCount) / median(tEncode)));
        System.out.printf(
                "toknroll count vs jtokkit count:  %.2fx%n", median(jCount) / median(tCount));
    }

    private static void report(String label, long[] ns, int tokens) {
        long[] s = ns.clone();
        Arrays.sort(s);
        double medMs = s[s.length / 2] / 1e6;
        System.out.printf(
                "%-24s median %7.2f ms | min %7.2f ms | %6.2f Mtok/s%n",
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
