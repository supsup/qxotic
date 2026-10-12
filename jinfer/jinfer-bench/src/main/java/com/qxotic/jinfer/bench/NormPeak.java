// Microbench: Norms.rmsnorm and Norms.sumOfSquares over one row - the square-sum reduction every
// transformer block runs before attention and before the MLP, once per token in decode.
//
// One row per size, re-read from cache on every call, so the number is the kernel's own speed
// (the reduction chain and the scale pass) and not DRAM bandwidth. The ladder holds hidden widths
// models actually have, plus odd neighbours that leave a ragged tail at every lane width. Size
// arguments replace the ladder.
//
// Build jinfer-bench, then run this main class with the same -cp as EmbedBench; the vector width
// and JIT verdict print first, since both change what is measured.
package com.qxotic.jinfer.bench;

import com.qxotic.jinfer.Segments;
import com.qxotic.jinfer.Views;
import com.qxotic.jinfer.kernels.Norms;
import com.qxotic.jota.memory.MemoryAllocators;
import com.qxotic.jota.memory.MemoryView;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;

public final class NormPeak {

    private static final int[] LADDER = {1024, 1027, 2048, 4096, 4099, 8192};

    /** Warm up until the last WINDOW passes agree within TOL, within the wall-clock bounds. */
    private static final int WINDOW = 3;

    private static final double TOL = 0.03;
    private static final int REPS = 5;
    private static final long MIN_WARMUP_NANOS = 1_500_000_000L;
    private static final long MAX_WARMUP_NANOS = 6_000_000_000L;

    /** Lanes per timed pass, so a pass is tens of milliseconds at every size. */
    private static final long LANES_PER_PASS = 1L << 24;

    private static volatile float sink; // blackhole so sumOfSquares survives DCE

    @FunctionalInterface
    private interface Pass {
        double seconds(
                MemoryView<MemorySegment> out,
                MemoryView<MemorySegment> x,
                MemoryView<MemorySegment> weight,
                int size,
                int calls);
    }

    public static void main(String[] args) {
        int[] sizes =
                args.length > 0
                        ? Arrays.stream(args).mapToInt(Integer::parseInt).toArray()
                        : LADDER;
        System.out.printf(
                "vectorBits=%d  vectorJit=%s%n%n",
                Segments.vectorBits(), Segments.FAST_VECTOR_JIT ? "fast" : "slow");
        System.out.printf("%-14s %6s %12s %10s%n", "kernel", "size", "ns/call", "lanes/ns");
        for (int size : sizes) {
            measure("rmsnorm", size, NormPeak::rmsnorm);
            measure("sumOfSquares", size, NormPeak::sumOfSquares);
        }
    }

    private static void measure(String kernel, int size, Pass pass) {
        try (Arena arena = Arena.ofConfined()) {
            var memory = MemoryAllocators.ofArena(arena);
            Random random = new Random(42);
            float[] values = new float[size];
            for (int i = 0; i < size; i++) values[i] = random.nextFloat() * 2 - 1;
            float[] weights = new float[size];
            for (int i = 0; i < size; i++) weights[i] = 0.5f + random.nextFloat();
            var x = Views.fromFloatArray(memory, values);
            var weight = Views.fromFloatArray(memory, weights);
            var out = Views.allocateF32(memory, size);
            int calls = (int) Math.max(1, LANES_PER_PASS / size);

            double[] recent = new double[WINDOW];
            long warmupStart = System.nanoTime();
            for (int p = 0; ; p++) {
                recent[p % WINDOW] = pass.seconds(out, x, weight, size, calls);
                long spent = System.nanoTime() - warmupStart;
                if (spent >= MAX_WARMUP_NANOS) break;
                if (spent >= MIN_WARMUP_NANOS && p >= WINDOW && stable(recent)) break;
            }
            double[] timings = new double[REPS];
            for (int rep = 0; rep < REPS; rep++) {
                timings[rep] = pass.seconds(out, x, weight, size, calls);
            }
            Arrays.sort(timings);
            double nsPerCall = timings[REPS / 2] * 1e9 / calls;
            System.out.printf(
                    "%-14s %6d %12.1f %10.2f%n", kernel, size, nsPerCall, size / nsPerCall);
        }
    }

    private static boolean stable(double[] recent) {
        double low = Double.MAX_VALUE, high = 0;
        for (double t : recent) {
            low = Math.min(low, t);
            high = Math.max(high, t);
        }
        return (high - low) / low <= TOL;
    }

    private static double rmsnorm(
            MemoryView<MemorySegment> out,
            MemoryView<MemorySegment> x,
            MemoryView<MemorySegment> weight,
            int size,
            int calls) {
        long t0 = System.nanoTime();
        for (int c = 0; c < calls; c++) Norms.rmsnorm(out, 0, x, 0, weight, size, 1e-5f);
        return (System.nanoTime() - t0) / 1e9;
    }

    private static double sumOfSquares(
            MemoryView<MemorySegment> out,
            MemoryView<MemorySegment> x,
            MemoryView<MemorySegment> weight,
            int size,
            int calls) {
        float total = 0;
        long t0 = System.nanoTime();
        for (int c = 0; c < calls; c++) total += Norms.sumOfSquares(x, 0, size);
        double seconds = (System.nanoTime() - t0) / 1e9;
        sink = total;
        return seconds;
    }
}
