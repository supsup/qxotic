package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import com.qxotic.toknroll.testkit.TiktokenFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reports, per corpus, how far the pre-fix ByteLevel behaviour drifted from the reference
 * tokenizer, and confirms the fixed behaviour matches it exactly.
 *
 * <p>Both arms come from ONE jar: staging {@code use_regex: false} into the tokenizer.json
 * reproduces the identity splitter the loader used to return for every ByteLevel, so no rebuild
 * sits between the two columns. The reference is the same encoding loaded from {@code
 * r50k_base.tiktoken}.
 *
 * <p>The error is reported per corpus because it is NOT uniform: it is driven by how much
 * whitespace-run structure the text has, so prose, minified JSON and indented configuration drift
 * by very different amounts. A single corpus would understate or overstate it.
 *
 * <p>args: [0] a GPT-2 tokenizer.json, [1..n] corpus files
 */
public final class ByteLevelCorpusShapeProbe {

    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Tokenizer fixed = stage(json, true);
        Tokenizer old = stage(json, false);
        Tokenizer reference = TiktokenFixtures.createTiktokenTokenizer("r50k_base");
        Tokenizer jtokkit = TiktokenFixtures.createJtokkitTokenizer("r50k_base");

        System.out.printf(
                "%-20s %10s %10s %10s %10s %9s %10s %8s %8s%n",
                "corpus",
                "chars",
                "tiktoken",
                "fixed",
                "pre-fix",
                "count err",
                "pre-fix vs",
                "fixed vs",
                "jtokkit");
        for (int i = 1; i < args.length; i++) {
            Path path = Path.of(args[i]);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            int[] ref = reference.encodeToArray(text);
            int[] now = fixed.encodeToArray(text);
            int[] was = old.encodeToArray(text);
            double err = 100.0 * (was.length - ref.length) / ref.length;
            System.out.printf(
                    "%-20s %10d %10d %10d %10d %8.2f%% %10s %8s %8s%n",
                    path.getFileName(),
                    text.length(),
                    ref.length,
                    now.length,
                    was.length,
                    err,
                    Arrays.equals(was, ref) ? "EXACT" : "DIFFERS",
                    Arrays.equals(now, ref) ? "EXACT" : "DIFFERS",
                    Arrays.equals(now, jtokkit.encodeToArray(text)) ? "EXACT" : "DIFFERS");
        }
    }

    private static Tokenizer stage(String tokenizerJson, boolean useRegex) throws Exception {
        String marker = "\"type\":\"ByteLevel\"";
        String spaced = "\"type\": \"ByteLevel\"";
        int at = tokenizerJson.indexOf(marker);
        int width = marker.length();
        if (at < 0) {
            at = tokenizerJson.indexOf(spaced);
            width = spaced.length();
        }
        if (at < 0) {
            throw new IllegalStateException("no ByteLevel pre_tokenizer in the tokenizer.json");
        }
        if (tokenizerJson.contains("use_regex")) {
            throw new IllegalStateException("the source tokenizer.json already sets use_regex");
        }
        String patched =
                tokenizerJson.substring(0, at + width)
                        + ",\"use_regex\":"
                        + useRegex
                        + tokenizerJson.substring(at + width);
        Path staged = Files.createTempDirectory("bytelevel-shape").resolve("tokenizer.json");
        Files.writeString(staged, patched, StandardCharsets.UTF_8);
        return HuggingFaceTokenizerLoader.fromLocal(staged);
    }
}
