package com.qxotic.toknroll.benchmarks;

import com.qxotic.toknroll.Tokenizer;
import com.qxotic.toknroll.hf.HuggingFaceTokenizerLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures what a ByteLevel pre-tokenizer's {@code use_regex} flag costs, as a function of input
 * length.
 *
 * <p>NO CODE CHANGE IS NEEDED TO GET BOTH ARMS. {@code use_regex: false} makes the loader return
 * {@link com.qxotic.toknroll.Splitter#identity()}, which is exactly what it used to return for
 * EVERY ByteLevel pre-tokenizer. So the {@code false} arm reproduces the pre-fix behaviour and the
 * {@code true} arm is the current one, in one JVM, from one jar, as a JMH parameter. That removes
 * the "were the two runs really built from different trees" doubt that a rebuild-between-runs
 * comparison always carries.
 *
 * <p>The size sweep is the point. BPE merge cost is superlinear in CHUNK length, and without the
 * regex the chunk is the whole document, so the gap should WIDEN with input size rather than sit at
 * a constant factor. A flat ratio across the sweep would falsify that explanation.
 *
 * <p>Inputs, set with system properties:
 *
 * <pre>
 *   -Dtoknroll.bench.tokenizerJson=/path/to/gpt2/tokenizer.json
 *   -Dtoknroll.bench.corpus=/path/to/corpus.txt
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class ByteLevelSplitJmhBenchmark {

    /** "true" is the fixed behaviour; "false" reproduces the identity splitter it replaced. */
    @Param({"true", "false"})
    public String useRegex;

    @Param({"1", "8", "64", "512"})
    public int sizeKiB;

    private Tokenizer tokenizer;
    private String text;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path source = Path.of(requireProperty("toknroll.bench.tokenizerJson"));
        Path corpus = Path.of(requireProperty("toknroll.bench.corpus"));

        String json = Files.readString(source, StandardCharsets.UTF_8);
        String patched = withUseRegex(json, Boolean.parseBoolean(useRegex));
        Path staged = Files.createTempDirectory("bytelevel-bench").resolve("tokenizer.json");
        Files.writeString(staged, patched, StandardCharsets.UTF_8);
        tokenizer = HuggingFaceTokenizerLoader.fromLocal(staged);

        String whole = Files.readString(corpus, StandardCharsets.UTF_8);
        int want = sizeKiB * 1024;
        StringBuilder sb = new StringBuilder(want + whole.length());
        while (sb.length() < want) {
            sb.append(whole);
        }
        text = sb.substring(0, want);
    }

    /**
     * Rewrites the ByteLevel pre-tokenizer's {@code use_regex} without a JSON library, because the
     * benchmark module should not need one to stage a one-field variant. The pre_tokenizer object
     * is located by its {@code "type": "ByteLevel"} marker and the flag is inserted right after it,
     * where a duplicate key cannot occur because the source file is asserted not to carry one.
     */
    private static String withUseRegex(String tokenizerJson, boolean value) {
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
            throw new IllegalStateException(
                    "the source tokenizer.json already sets use_regex; this benchmark stages the "
                            + "flag itself and will not overwrite an explicit one");
        }
        return tokenizerJson.substring(0, at + width)
                + ",\"use_regex\":"
                + value
                + tokenizerJson.substring(at + width);
    }

    private static String requireProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("set -D" + key);
        }
        return value;
    }

    @Benchmark
    public void encode(Blackhole blackhole) {
        blackhole.consume(tokenizer.encode(text));
    }

    @Benchmark
    public void countTokens(Blackhole blackhole) {
        blackhole.consume(tokenizer.countTokens(text));
    }
}
