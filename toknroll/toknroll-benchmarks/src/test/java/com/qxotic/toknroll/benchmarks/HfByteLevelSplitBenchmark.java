package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import com.qxotic.toknroll.testkit.TiktokenFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Times the HuggingFace-loaded GPT-2 tokenizer against the same encoding loaded from a tiktoken
 * file, and reports the token counts so a speed win that quietly changed the answer cannot pass as
 * a win.
 *
 * <p>args: [0] a HuggingFace tokenizer.json for GPT-2 [1] a UTF-8 corpus file
 *
 * <p>The tiktoken side reads {@code r50k_base.tiktoken} from {@code test-fixtures/tiktoken}, which
 * the repository downloads rather than vendors:
 *
 * <pre>
 * python3 toknroll/scripts/download_tiktoken_fixtures.py
 * </pre>
 */
public final class HfByteLevelSplitBenchmark {

    public static void main(String[] args) throws Exception {
        Tokenizer hf = HuggingFaceTokenizerLoader.fromLocal(Path.of(args[0]));
        Tokenizer tt = TiktokenFixtures.createTiktokenTokenizer("r50k_base");
        String corpus = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);

        System.out.printf(
                "corpus %,d chars | hf=%,d tokens | tiktoken=%,d tokens | agree=%s%n",
                corpus.length(),
                hf.countTokens(corpus),
                tt.countTokens(corpus),
                hf.countTokens(corpus) == tt.countTokens(corpus));

        for (int i = 0; i < 3; i++) {
            sink(hf.countTokens(corpus));
            sink(tt.countTokens(corpus));
        }

        double hfMs = time(hf, corpus);
        double ttMs = time(tt, corpus);
        System.out.printf("hf       %8.2f ms/pass%n", hfMs);
        System.out.printf("tiktoken %8.2f ms/pass%n", ttMs);
        System.out.printf("ratio    %8.2fx%n", hfMs / ttMs);
    }

    private static double time(Tokenizer t, String corpus) {
        int runs = 10;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            sink(t.countTokens(corpus));
        }
        return (System.nanoTime() - start) / 1_000_000.0 / runs;
    }

    private static int blackhole;

    private static void sink(int v) {
        blackhole ^= v;
    }
}
