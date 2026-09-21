package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import com.qxotic.toknroll.testkit.TiktokenFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Finds the SHORTEST prefix of a corpus where the HF-loaded and tiktoken tokenizers disagree. */
public final class HfVsTiktokenParityProbe {
    public static void main(String[] args) throws Exception {
        Tokenizer hf = HuggingFaceTokenizerLoader.fromLocal(Path.of(args[0]));
        Tokenizer tt = TiktokenFixtures.createTiktokenTokenizer("r50k_base");
        String corpus = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);

        System.out.printf(
                "whole corpus: hf=%,d tt=%,d%n", hf.countTokens(corpus), tt.countTokens(corpus));

        // Binary search the shortest diverging prefix.
        int lo = 1, hi = corpus.length();
        if (Arrays.equals(hf.encodeToArray(corpus), tt.encodeToArray(corpus))) {
            System.out.println("no divergence on the whole corpus");
            return;
        }
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            String p = corpus.substring(0, mid);
            if (Arrays.equals(hf.encodeToArray(p), tt.encodeToArray(p))) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        String minimal = corpus.substring(0, lo);
        String tail = minimal.substring(Math.max(0, minimal.length() - 60));
        System.out.printf("%nshortest diverging prefix: %d chars%n", lo);
        System.out.printf("tail: %s%n", escape(tail));
        int[] a = hf.encodeToArray(minimal);
        int[] b = tt.encodeToArray(minimal);
        System.out.printf("hf=%d tokens, tt=%d tokens%n", a.length, b.length);
        int i = 0;
        while (i < Math.min(a.length, b.length) && a[i] == b[i]) i++;
        System.out.printf(
                "first differing token index %d: hf=%d tt=%d%n",
                i, i < a.length ? a[i] : -1, i < b.length ? b[i] : -1);
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
