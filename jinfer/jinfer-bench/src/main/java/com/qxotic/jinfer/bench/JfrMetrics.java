package com.qxotic.jinfer.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * The numbers a performance question needs, from one recording, as one table: prompt-processing and
 * decode throughput, decode step latency percentiles, model load and tokenizer cost from jinfer's
 * own events; GC pauses, allocation rate and CPU load from the JDK's. What {@code jfr summary}
 * counts and {@code jfr print} lists, reduced. Reads a file; records nothing.
 *
 * <p>Throughput comes from the per-chunk and per-token events when the recording has them ({@code
 * jinfer --jfr} switches {@code jinfer.Decode} on) and from the per-request {@code
 * jinfer.Inference} timings otherwise; a row whose events are absent reads {@code n/a} rather than
 * zero. Allocation prefers the exact per-thread counters ({@code jdk.ThreadAllocationStatistics},
 * in the JDK's default settings) over the sampled weights ({@code jdk.ObjectAllocationSample}).
 *
 * <pre>java -cp jinfer-bench.jar com.qxotic.jinfer.bench.JfrMetrics run.jfr</pre>
 */
public final class JfrMetrics {

    private JfrMetrics() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].startsWith("-")) {
            System.err.println("usage: JfrMetrics <recording.jfr>");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        if (!Files.isRegularFile(file)) {
            System.err.println("no such file: " + file);
            System.exit(1);
        }
        read(file).print(System.out);
    }

    /** One pass over the file; events the report does not know are counted, nothing more. */
    static Report read(Path file) throws IOException {
        Report report = new Report();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) report.add(recording.readEvent());
        }
        return report;
    }

    /** The accumulators, one field per number the table prints. Package-visible for its test. */
    static final class Report {
        long events;
        Instant first, last;

        int loads;
        long loadNanos, weightsBytes;
        String model = "", architecture = "";

        int tokenizations;
        long tokenizeChars, tokenizeTokens, tokenizeNanos;

        int prefillChunks;
        long prefillTokens, prefillNanos;

        final List<Long> decodeSteps = new ArrayList<>();
        long decodeNanos;

        int inferences;
        long inputTokens, cachedTokens, outputTokens, inferencePrefillNanos, inferenceDecodeNanos;
        final List<Long> firstTokenNanos = new ArrayList<>();

        int gcs;
        long gcPauseNanos, gcLongestNanos;

        /** Per JFR thread id: the first and the latest cumulative {@code allocated} reading. */
        final Map<Long, long[]> threadAllocated = new HashMap<>();

        long sampledAllocationBytes;

        int cpuSamples;
        double jvmUser, jvmSystem, machineTotal;

        void add(RecordedEvent event) {
            events++;
            Instant start = event.getStartTime();
            Instant end = event.getEndTime();
            if (first == null || start.isBefore(first)) first = start;
            if (last == null || end.isAfter(last)) last = end;
            switch (event.getEventType().getName()) {
                case "jinfer.ModelLoad" -> {
                    loads++;
                    loadNanos += event.getDuration().toNanos();
                    weightsBytes += event.getLong("weightsBytes");
                    model = event.getString("model");
                    architecture = event.getString("architecture");
                }
                case "jinfer.Tokenize" -> {
                    tokenizations++;
                    tokenizeChars += event.getInt("chars");
                    tokenizeTokens += event.getInt("tokens");
                    tokenizeNanos += event.getDuration().toNanos();
                }
                case "jinfer.Prefill" -> {
                    prefillChunks++;
                    prefillTokens += event.getInt("tokens");
                    prefillNanos += event.getDuration().toNanos();
                }
                case "jinfer.Decode" -> {
                    long nanos = event.getDuration().toNanos();
                    decodeSteps.add(nanos);
                    decodeNanos += nanos;
                }
                case "jinfer.Inference" -> {
                    inferences++;
                    inputTokens += event.getInt("inputTokens");
                    cachedTokens += event.getInt("cachedTokens");
                    outputTokens += event.getInt("outputTokens");
                    inferencePrefillNanos += event.getLong("prefillTime");
                    inferenceDecodeNanos += event.getLong("decodeTime");
                    long firstToken = event.getLong("timeToFirstToken");
                    if (firstToken > 0) firstTokenNanos.add(firstToken);
                }
                case "jdk.GarbageCollection" -> {
                    gcs++;
                    gcPauseNanos += event.getDuration("sumOfPauses").toNanos();
                    gcLongestNanos =
                            Math.max(gcLongestNanos, event.getDuration("longestPause").toNanos());
                }
                case "jdk.ThreadAllocationStatistics" -> {
                    RecordedThread thread = event.getThread("thread");
                    if (thread == null) break;
                    long allocated = event.getLong("allocated");
                    long[] range =
                            threadAllocated.computeIfAbsent(
                                    thread.getId(), id -> new long[] {allocated, allocated});
                    range[1] = allocated;
                }
                case "jdk.ObjectAllocationSample" -> {
                    sampledAllocationBytes += event.getLong("weight");
                }
                case "jdk.CPULoad" -> {
                    cpuSamples++;
                    jvmUser += event.getFloat("jvmUser");
                    jvmSystem += event.getFloat("jvmSystem");
                    machineTotal += event.getFloat("machineTotal");
                }
                default -> {}
            }
        }

        /** First event start to last event end; zero for an empty recording. */
        Duration span() {
            return first == null ? Duration.ZERO : Duration.between(first, last);
        }

        int decodeSteps() {
            return decodeSteps.size();
        }

        /** Decode tokens per second: per-token events first, per-request timings otherwise. */
        double decodeTokensPerSecond() {
            if (decodeNanos > 0) return perSecond(decodeSteps.size(), decodeNanos);
            return perSecond(outputTokens, inferenceDecodeNanos);
        }

        /** Prompt tokens per second: per-chunk events first, per-request timings otherwise. */
        double prefillTokensPerSecond() {
            if (prefillNanos > 0) return perSecond(prefillTokens, prefillNanos);
            return perSecond(inputTokens - cachedTokens, inferencePrefillNanos);
        }

        /** The {@code q}-quantile of the decode step durations, nearest-rank; 0 with no steps. */
        long decodeStepNanos(double q) {
            return percentile(decodeSteps, q);
        }

        /** Bytes allocated: exact per-thread deltas when sampled, the sample weights otherwise. */
        long allocatedBytes() {
            if (threadAllocated.isEmpty()) return sampledAllocationBytes;
            long total = 0;
            for (long[] range : threadAllocated.values()) total += range[1] - range[0];
            return total;
        }

        void print(PrintStream out) {
            out.print(render());
        }

        String render() {
            StringBuilder table = new StringBuilder("| metric | value |\n| ------ | ----- |\n");
            row(table, "recording", seconds(span().toNanos()) + ", " + events + " events");
            row(
                    table,
                    "model load",
                    loads == 0
                            ? "n/a"
                            : seconds(loadNanos)
                                    + ", "
                                    + bytes(weightsBytes)
                                    + " weights"
                                    + (architecture.isEmpty() ? "" : ", " + architecture)
                                    + (model.isEmpty() ? "" : ", " + model)
                                    + (loads > 1 ? ", " + loads + " loads" : ""));
            row(
                    table,
                    "tokenizer",
                    tokenizations == 0
                            ? "n/a"
                            : format(
                                    "%d prompts, %d chars -> %d tokens, %s chars/s",
                                    tokenizations,
                                    tokenizeChars,
                                    tokenizeTokens,
                                    rate(perSecond(tokenizeChars, tokenizeNanos))));
            row(
                    table,
                    "prefill",
                    prefillChunks == 0 && inferencePrefillNanos == 0
                            ? "n/a"
                            : format(
                                    "%d tokens in %d chunks, %s t/s",
                                    prefillChunks > 0 ? prefillTokens : inputTokens - cachedTokens,
                                    prefillChunks,
                                    rate(prefillTokensPerSecond())));
            row(
                    table,
                    "decode",
                    decodeSteps.isEmpty() && inferenceDecodeNanos == 0
                            ? "n/a"
                            : decodeSteps.isEmpty()
                                    ? format(
                                            "%d tokens, %s t/s (per-request timings; enable"
                                                    + " jinfer.Decode for step latencies)",
                                            outputTokens, rate(decodeTokensPerSecond()))
                                    : format(
                                            "%d tokens, %s t/s, step p50 %s, p95 %s, max %s",
                                            decodeSteps.size(),
                                            rate(decodeTokensPerSecond()),
                                            seconds(decodeStepNanos(0.50)),
                                            seconds(decodeStepNanos(0.95)),
                                            seconds(decodeStepNanos(1.0))));
            row(
                    table,
                    "time to first token",
                    firstTokenNanos.isEmpty()
                            ? "n/a"
                            : format(
                                    "p50 %s, max %s over %d requests",
                                    seconds(percentile(firstTokenNanos, 0.50)),
                                    seconds(percentile(firstTokenNanos, 1.0)),
                                    firstTokenNanos.size()));
            row(
                    table,
                    "gc",
                    gcs == 0
                            ? "no collections"
                            : format(
                                    "%d collections, %s paused, longest %s",
                                    gcs, seconds(gcPauseNanos), seconds(gcLongestNanos)));
            long allocated = allocatedBytes();
            row(
                    table,
                    "allocation",
                    threadAllocated.isEmpty() && sampledAllocationBytes == 0
                            ? "n/a"
                            : bytes(allocated)
                                    + ", "
                                    + bytes((long) perSecond(allocated, span().toNanos()))
                                    + "/s"
                                    + (threadAllocated.isEmpty() ? " (sampled)" : ""));
            row(
                    table,
                    "cpu",
                    cpuSamples == 0
                            ? "n/a"
                            : format(
                                    "jvm %.1f %% (user %.1f %%, system %.1f %%), machine %.1f %%",
                                    100 * (jvmUser + jvmSystem) / cpuSamples,
                                    100 * jvmUser / cpuSamples,
                                    100 * jvmSystem / cpuSamples,
                                    100 * machineTotal / cpuSamples));
            return table.toString();
        }

        private static void row(StringBuilder table, String metric, String value) {
            table.append("| ").append(metric).append(" | ").append(value).append(" |\n");
        }
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private static double perSecond(long count, long nanos) {
        return nanos <= 0 ? 0 : count * 1e9 / nanos;
    }

    private static long percentile(List<Long> values, double q) {
        if (values.isEmpty()) return 0;
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int rank = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    /** A rate with the precision its magnitude earns. */
    static String rate(double perSecond) {
        if (perSecond >= 1_000_000) return format("%.2f M", perSecond / 1e6);
        if (perSecond >= 10_000) return format("%.1f k", perSecond / 1e3);
        return format("%.1f", perSecond);
    }

    /** Nanoseconds as seconds, milliseconds or microseconds, whichever reads without zeros. */
    static String seconds(long nanos) {
        if (nanos >= 1_000_000_000L) return format("%.2f s", nanos / 1e9);
        if (nanos >= 1_000_000L) return format("%.1f ms", nanos / 1e6);
        return format("%.1f us", nanos / 1e3);
    }

    /** Bytes in the largest binary unit that keeps the number above one. */
    static String bytes(long bytes) {
        if (bytes >= 1L << 30) return format("%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return format("%.1f MB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return format("%.1f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }
}
