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
 * zero.
 *
 * <p>Allocation names its source, because the three are not the same claim. {@code
 * jdk.ThreadAllocationStatistics} is a CUMULATIVE per-thread counter, so one reading of a thread
 * says nothing about the recording and two give an exact delta: {@code exact-delta} when every
 * thread seen has two, {@code partial} when only some do (their deltas, and how many). With no
 * thread read twice the figure is the sum of the {@code jdk.ObjectAllocationSample} weights,
 * labelled {@code sampled} - an estimate. With neither it reads {@code unavailable}, never {@code 0
 * B}.
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

        /**
         * Per JFR thread id: the first cumulative {@code allocated} reading, the latest, and how
         * many readings there were. One reading is a level, not a delta.
         */
        final Map<Long, long[]> threadAllocated = new HashMap<>();

        int allocationSamples;
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
                    threadAllocated(thread.getId(), event.getLong("allocated"));
                }
                case "jdk.ObjectAllocationSample" -> allocationSample(event.getLong("weight"));
                case "jdk.CPULoad" -> {
                    cpuSamples++;
                    jvmUser += event.getFloat("jvmUser");
                    jvmSystem += event.getFloat("jvmSystem");
                    machineTotal += event.getFloat("machineTotal");
                }
                default -> {}
            }
        }

        /** One cumulative {@code jdk.ThreadAllocationStatistics} reading of one thread. */
        void threadAllocated(long threadId, long allocated) {
            long[] range =
                    threadAllocated.computeIfAbsent(
                            threadId, id -> new long[] {allocated, allocated, 0});
            range[1] = allocated;
            range[2]++;
        }

        /** One {@code jdk.ObjectAllocationSample}; its weight estimates the bytes it stands for. */
        void allocationSample(long weight) {
            allocationSamples++;
            sampledAllocationBytes += weight;
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

        /**
         * Bytes allocated over the recording, with where the number came from. A thread's
         * cumulative counter contributes only when it was read at least twice: a single reading has
         * first == last, and that zero is the absence of a delta, not a measurement of one.
         */
        Allocation allocation() {
            long delta = 0;
            int measured = 0;
            for (long[] range : threadAllocated.values()) {
                if (range[2] < 2) continue;
                delta += range[1] - range[0];
                measured++;
            }
            int seen = threadAllocated.size();
            if (measured > 0) {
                return new Allocation(
                        measured == seen ? AllocationSource.EXACT_DELTA : AllocationSource.PARTIAL,
                        delta,
                        measured,
                        seen);
            }
            if (allocationSamples > 0) {
                return new Allocation(AllocationSource.SAMPLED, sampledAllocationBytes, 0, seen);
            }
            return new Allocation(AllocationSource.UNAVAILABLE, 0, 0, seen);
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
            row(table, "allocation", allocation().render(span().toNanos()));
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

    /** Where an allocation figure came from; the label is what the table prints. */
    enum AllocationSource {
        /** Every thread seen was read twice: the sum of their counter deltas. */
        EXACT_DELTA("exact-delta"),
        /** Only some threads were read twice: the deltas of those, a lower bound. */
        PARTIAL("partial"),
        /** No thread was read twice: the sum of the allocation sample weights, an estimate. */
        SAMPLED("sampled"),
        /** No thread read twice and no samples: there is no number to print. */
        UNAVAILABLE("unavailable");

        final String label;

        AllocationSource(String label) {
            this.label = label;
        }
    }

    /**
     * An allocation figure and its source. {@code threads} is how many threads contributed a
     * counter delta, {@code threadsSeen} how many had any counter reading; {@code bytes} means
     * nothing when the source is {@link AllocationSource#UNAVAILABLE}.
     */
    record Allocation(AllocationSource source, long bytes, int threads, int threadsSeen) {

        /** The table cell: the number, its rate over a non-empty span, and the source. */
        String render(long spanNanos) {
            if (source == AllocationSource.UNAVAILABLE) return source.label;
            // an empty span has no rate; printing one would be the same false zero as the bytes
            String rate =
                    spanNanos > 0
                            ? ", " + JfrMetrics.bytes((long) perSecond(bytes, spanNanos)) + "/s"
                            : "";
            String detail =
                    switch (source) {
                        case EXACT_DELTA -> format("%s, %d threads", source.label, threads);
                        case PARTIAL ->
                                format(
                                        "%s: exact-delta of %d of %d threads",
                                        source.label, threads, threadsSeen);
                        default -> source.label + ", an estimate";
                    };
            return JfrMetrics.bytes(bytes) + rate + " (" + detail + ")";
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
