package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prints a token count and an FNV-1a hash of the whole token array for each model directory given,
 * so a change to pre-tokenization can be shown to leave a family bit-for-bit unchanged rather than
 * merely unchanged in LENGTH.
 *
 * <p>Run it once before a change and once after; every line that should not move must match on BOTH
 * columns. A load failure is printed rather than thrown, because a family that cannot load is still
 * a row worth comparing across the two runs.
 *
 * <p>args: [0] a UTF-8 corpus file, [1..n] model directories each holding a tokenizer.json
 */
public final class HfPreTokenizerInvarianceProbe {

    public static void main(String[] args) throws Exception {
        String corpus = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        for (int i = 1; i < args.length; i++) {
            String name = Path.of(args[i]).getFileName().toString();
            try {
                Tokenizer tokenizer = HuggingFaceTokenizerLoader.fromLocal(Path.of(args[i]));
                int[] tokens = tokenizer.encodeToArray(corpus);
                System.out.printf(
                        "%-32s tokens=%,9d  fnv=%016x%n", name, tokens.length, fnv(tokens));
            } catch (RuntimeException ex) {
                System.out.printf("%-32s LOAD FAILED: %s%n", name, ex.getMessage());
            }
        }
    }

    private static long fnv(int[] tokens) {
        long hash = 0xcbf29ce484222325L;
        for (int token : tokens) {
            hash ^= token;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
