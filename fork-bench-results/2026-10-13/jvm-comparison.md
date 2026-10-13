# JVM comparison on the Mac, 2026-10-13

Machine: Apple M5 Max, 6 performance cores, 18 logical, 128 GB, macOS 27. Model LFM2.5-350M Q8_0. Backend jam vector (no native matmul: no cmake on this host). One JVM at a time, machine otherwise idle. The sweep cells use the bench jar from fixpoint/bench-thread-sweep 02ada9895; the recorded runs use the jar from fixpoint/jfr-metrics 319d97e15 (same engine plus the JFR events). pp512, tg128, 5 timed reps after adaptive warm-up for the sweeps; 3 reps for the recorded runs.

## The four host cells (tokens per second, mean of 5 reps)

| threads | H1 Corretto 25.0.2 C2 pp | H2 GraalVM CE 25.4.4.1 pp | D same build, C2 forced pp | H1 tg | H2 tg | D tg |
|---|---|---|---|---|---|---|
| 1 | 130.3 | 127.4 | 129.9 | 12.9 | 32.8 | 13.0 |
| 2 | 231.1 | 235.7 | 233.7 | 22.9 | 59.8 | 22.2 |
| 4 | 376.1 | 389.6 | 401.4 | 40.2 | 103.1 | 40.2 |
| 6 | 569.8 | 541.6 | 553.5 | 55.8 | 139.7 | 53.4 |
| 8 | 710.1 | 683.3 | 699.0 | 68.2 | 165.2 | 64.2 |

H3, GraalVM CE 25.0.0: 1 thread pp 71.2 tok/s (half of the others), then tg128 warm-up at 0.06 tok/s. Killed after 65 minutes; the file carries a FAILED line. Not a slower JIT, an unusable one for this decode path.

## What the cells say

1. Prefill does not depend on the JIT here: all three columns agree within 4 percent at every thread count, and the standard deviations overlap at most rows.
2. Decode depends on the JIT and on nothing else measured: 2.4 to 2.5x under the Graal JIT at every thread count, and the same GraalVM binary with C2 forced reproduces Corretto to within noise. The JDK build, the vendor and the sweep order are ruled out.
3. Both JITs scale decode about 5.3x from 1 to 8 threads, so at this model size the Mac is not memory-bound on decode; the C2 cost is compute inside the kernel, not bandwidth.
4. The engine's SLOW_JIT cliff and the C2 (Vector API kernels de-intrinsified) header are still set by compiler identity (Segments.vectorJitDetected). On this machine the label is wrong about prefill and right about decode. Both prefill and decode take the same band route (Q8Kernel.gemm -> BandGemm: dequantizeRow to float scratch, then a 3x3 band on the 128-bit preferred species); the 256-bit species in Q8Kernel are x86-only paths and the NEON register tiles are opt-in (-Djam.vector.q8=tile), so tile selection is the same under both JITs and is not the cause.
5. Decode-only ratio prefill/decode: 10x under C2, 3.9x under Graal. The upstream author reports 7.4x at one thread on his x86 laptop and 19x with all threads.

## Recorded runs, intrinsics log and controls

(filled in below from the 01:22Z run)

Run at 01:22Z with the jfr-metrics jar (319d97e15; that branch has no bench-jar Makefile target and no --threads-sweep: build with `mvn -pl jinfer/jinfer-bench -am clean package -DskipTests -Djam.native.skip=true`, run with -t 4), 4 threads, pp512 tg128 r3 w2, -XX:StartFlightRecording settings=profile. Recordings: fork-bench-results/2026-10-13/jfr/corretto-25.0.2-c2-t4.jfr (8.5 MB) and graalce-25.4.4.1-t4.jfr (1.4 MB); read with `jfr view hot-methods | allocation-by-class | gc-pauses | compiler-statistics`.

### Where the time goes (jfr view hot-methods, 4 threads)

| | Corretto C2 | GraalVM 25.4 JIT |
|---|---|---|
| top frame | jdk.incubator.vector.FloatVector.lanewiseTemplate(Ternary, Vector, Vector) 55.2% | jdk.internal.foreign.MemorySessionImpl.checkValidStateRaw 75.3% (an inlined leaf; Graal attributes the kernel's samples to it) |
| second | com.qxotic.jam.vector.BandGemm.sweepPanel33 19.1% | Parallel$Worker.run 11.4% |
| also | MatMul.dotQ8Generic 7.0%, FloatVector.zero 2.8%, AbstractVector.convertShapeTemplate 0.9% | VectorSupport.ternaryOp 1.6%, FlashAttention decode 2% |

`lanewiseTemplate` is the Vector API's JAVA implementation of a lanewise ternary op (the FMA): it runs only when the call did not become the C2 intrinsic. Under C2, more than half of all decode samples are inside that fallback. Under Graal it does not appear.

### Allocation and GC

| | Corretto C2 | GraalVM 25.4 JIT |
|---|---|---|
| allocation by class | float[] 93.5%, Float128Vector 6.4% | float[] 31.8%, Byte256Vector 17.1%, Float128Vector 16.5% (small total) |
| GC pauses | 563 pauses, 313 ms total | 6 pauses, 17.8 ms total |
| compiled methods / total compile time | 4,683 / 4.0 s (123 OSR) | 4,708 / 8.4 s (122 OSR) |

Under C2 the decode loop materializes vectors: every float[] is the payload of a boxed Float128Vector, and the collector runs 563 times in a 3-rep run. That is the signature of a Vector API value crossing a call that was not inlined, after which each lanewise op allocates its result and takes the Java path.

### Why C2 stops inlining (PrintIntrinsics + PrintCompilation, Corretto, 4 threads, full run; e2-corretto.log, 71,171 lines)

- 3,488 Vector API intrinsic records, 711 refusals, every refusal on a 32-lane BYTE vector (`** not supported: ... vlen=32 etype=byte`: extract 423, broadcast 31, cast 21, reinterpret 12, compare/and/blend/load/store the rest). A 32-lane byte vector is ByteVector.SPECIES_256; NEON holds 128 bits, so C2 declines every op on it and the Java fallback extracts lane by lane. The engine's own byte species (Segments.B_SPECIES) is 128-bit on this machine, so these come from the explicit SPECIES_256 loads in jinfer-kernels Convert.dequantQ8_0Rows:404 (the embedding-row gather) and MatMul.blockFma's 256-bit arm (not taken here). Real, but a side cost: one embedding row per token.
- No refusal on any float op: every 4-lane float op (FMA, add, mul, cast from bytes) was accepted when C2 reached it as an intrinsic.
- The hot kernels are large: BandGemm.sweepPanel33 is 725 bytecodes and compiles 10 times (5 made-not-entrant, uncommon traps), sweepEdge33 14 times, FlashAttention.lambda$prefill$0 18 times. Inlining failures recorded inside engine kernels: `hot method too big` 25 (FlashAttention qkTile 672 bytes, pvTile 457, accumulate 395, expRowInPlace 440; MatMul.blockFma 566), `NodeCountInliningCutoff` for BandGemm.add and FlashAttention.normalize, `already compiled into a big method` for MatMul.shapedMm, `size > DesiredMethodLimit` for Lfm2.feedForward. Vector API callees failing to inline: 46 `low call site frequency`, 41 `too big`, 12 `virtual call`; 45 records of AbstractSpecies.makeDummyVector and 25 of VectorOperators$ImplCache.badOp, which are the fallback entry points.
- Reading: once one helper that returns a FloatVector is not inlined into the kernel (budget, size or a receiver C2 cannot devirtualize), the vector crosses a call boundary, gets boxed, and every op downstream runs through lanewiseTemplate on a heap object. Graal's inliner keeps the whole chain in registers. The SLOW_JIT label names a true effect for decode on this machine, but the mechanism is C2's inlining budget on large Vector API kernels, not de-intrinsified vector instructions.

### Controls, decode only, 4 threads (tok/s)

| route | Corretto C2 | GraalVM 25.4 |
|---|---|---|
| band (default; the JFR runs) | 40.2 | 103.1 |
| register tile neon-4x4 (-Djam.vector.q8=tile) | 32.2 | 40.1 |
| scalar (-Djam.vector.q8=tile -Djam.vector.tile=scalar) | 32.7 | 40.0 |

On both JITs the NEON register tile is no faster than the scalar kernel (C2 32.2 vs 32.7; Graal 40.1 vs 40.0): the register-tile route gains nothing on this CPU, and the band route is the right default. The JIT gap lives in the band route alone (C2 40.2 vs Graal 103.1).

### The decisive control: C2 with a larger inlining budget (decode only, 4 threads, Corretto 25.0.2, jfr-metrics jar)

| arm | flags | tg128 tok/s |
|---|---|---|
| baseline | none | 32.6 |
| inline-budget | -XX:FreqInlineSize=2000 -XX:MaxInlineSize=200 -XX:InlineSmallCode=16000 -XX:MaxInlineLevel=40 -XX:MaxNodeLimit=300000 -XX:NodeLimitFudgeFactor=24000 | 86.7 |
| no-tiered | -XX:-TieredCompilation | 46.0 |
| both | the two above together | 85.4 |
| (reference) GraalVM CE 25.4.4.1, default | | 103.1 |

Product-build inlining flags alone recover 2.7x of C2 decode on this machine with no code change. That supports the mechanism (Lattice's independent run, PROJECT/stafficy/37918, reproduces the 2.7x; her phase and denominator objections to the JFR percentages are adopted below): C2 stops inlining the Vector API chain inside the large band kernel (hot method too big, NodeCountInliningCutoff, InlineSmallCode), the FloatVectors are boxed across the call, and every op downstream runs the Java fallback with a heap allocation. It is not a NEON instruction-selection problem and not a missing intrinsic. Turning off tiered compilation helps a little (fewer profile-driven recompiles) and adds nothing on top of the budget flags.

Note the tg-only baseline (32.6) is below the sweep's 4-thread decode (40.2) because the sweep's decode followed a prefill that had already warmed the kernels; compare arms within a table, not across tables.

## Conclusions

1. On Apple Silicon the engine's SLOW_JIT cliff is real for decode and its size depends on the process history (see Reconciliation): 3.3x when prefill ran first in the same JVM, 1.2x in a decode-only process. For fresh single-phase processes prefill is NOT equal: C2 about 1.45x Graal. Its detector (compiler identity) happens to be right here, by luck not by measurement; a self-timing check (the research note's proposal) would say the same thing for a reason.
2. The best-supported cause is C2's inlining budget on large Vector API kernels, which boxes vectors (the flag control is the strongest evidence; the JFR percentages are whole-run, not decode-only, and a jdk.CompilerInlining recording is still owed). Two remedies, both worth an upstream issue: (a) document the C2 flag set above as the supported way to run on OpenJDK/Corretto (it is a one-line JAVA_OPTS change and gives 84 percent of Graal); (b) split BandGemm.sweepPanel33 (725 bytecodes) and the FlashAttention tiles so the inlined Vector API chain fits C2's default budget, which fixes it without flags and would also help x86 C2 users.
3. The 256-bit byte species in Convert.dequantQ8_0Rows is a separate, smaller defect (one embedding row per token; 711 refusals per run; it is also 17 percent of allocation under Graal as Byte256Vector); patched as two SPECIES_128 loads on branch fixpoint/q8-dequant-neon (b9b4393b3), unbuilt and unmeasured.
4. GraalVM CE 25.0.0 must not be used for decode on this machine (0.06 tok/s). Only the 25.4 line carries the Graal advantage. The version gate in VectorSupport.wideBand (25.4 or newer) is already the right line for a different reason.
5. Decode is not memory-bound at this model size on this machine (5.3x scaling to 8 threads on both JITs); the author's memory-bound regime needs a larger model or a weaker core.
6. Not done: Linux container cells, the native matmul backend, an x86 control, llama.cpp on the same Mac, disassembly of the band kernel. All are listed in tools/matrix.md.

## Reconciliation with Lattice's independent run (PROJECT/stafficy/37918, 02:00Z)

Her decode-only arms: Corretto 30.19, Corretto with the flags 81.16, Graal 39.72; prefill-only Corretto 404.08, Graal 291.05. The Graal decode (39.7 against 103.1 above) and prefill (291 against "equal") did not reproduce.

Repeat, fresh JVM each, 4 threads (02:06-02:14Z): Graal decode-only 38.1, 38.7, 40.1, 40.2, 40.1; Graal prefill-only 291.5, 291.0, 291.7, 290.8, 290.0; Corretto decode-only 32.7, 32.6, 32.6. Her numbers, not the ones above.

History control, fresh JVM per arm, twice each (02:15-02:21Z):

| arm | Graal 25.4.4.1 tg128 | Corretto 25.0.2 tg128 |
|---|---|---|
| pp512 then tg128 in one JVM | 108.9, 107.5 | 33.0, 33.5 |
| tg128 alone (-p 0) | 38.4, 40.2 | 33.3, 33.3 |

C2 decode does not depend on what the process ran before. Graal decode does: 108 when the band kernel was compiled during a prefill in the same JVM, 39 when the process only ever decoded. Every fast Graal number above (the sweep H2 column, the JFR run) came from a prefill-first process; every slow one (hers, the repeat, the tile controls) from a decode-only process. Prefill for fresh processes: Graal 281 to 292, Corretto 388 to 427; the sweep's 390 for Graal at 4 threads came after the 1 and 2 thread passes in the same process.

What this changes: the Graal advantage on decode is 3.3x on the prefill-then-decode shape (the shape every chat request has) and 1.2x on a decode-only process. The decode-only bench arm understates Graal and the sweep overstates "fresh" Graal prefill. Why the Graal JIT's compile of the band kernel depends on the first profile it saw is NOT established; the next probe is a jdk.CompilerInlining recording of the two arms. The C2 flag result is unaffected: 2.7x in both labs.

## Other JDKs (02:21-02:27Z, same jar and model, 4 threads, fresh JVM per arm, sdkman installs)

| JDK | runtime label | decode-only default | decode-only with the six C2 flags | prefill then decode: pp512 / tg128 |
|---|---|---|---|---|
| Amazon Corretto 25.0.2 (reference, above) | C2 | 32.6 | 86.7 | 422 / 33.0 |
| Amazon Corretto 26.0.2 (JDK 26) | C2 | 32.3 | 84.0 | 445 / 32.2 |
| Eclipse Temurin 25.0.3 | C2 | 32.9 | 85.4 | 403 / 32.2 |
| GraalVM CE 25.4.4.1 (above) | GraalVM JIT | 38 to 40 | (flags are C2's) | 281 to 289 / 108 |
| Oracle GraalVM 25.4.4 (java 25.0.4.1.1, the commercial build) | GraalVM JIT | 112.6 | 114.2 (the C2 flags are inert here) | 453 / 110.2 |

Readings:
- The C2 decode cliff is the same on every HotSpot build, and JDK 26 does not fix it: 32 to 33 default, 84 to 87 with the inlining flags. Vendor and version are ruled out again; the panama-dev report, if filed, is against C2 itself.
- Oracle GraalVM has NO process-history dependence: decode-only 112.6 and prefill-first 110.2 are the same state, and its prefill (453) is at or above C2's. The decode-only slow state (39) is specific to GraalVM CE 25.4.4.1. The CE compiler lacks the enterprise optimizations of the Oracle build; which one of them is the difference is not established and is the next question for the Graal reading.
- Best number on this machine tonight, same jar, no flags, no code change: Oracle GraalVM, 453 prefill and 112 decode at 4 threads, against C2's 422 and 33.

## What is failing, named, and the fix (02:28-02:50Z)

Isolation by -XX:CompileCommand (Corretto 25.0.2, decode-only, 4 threads, fresh JVM each; logs inline-probe*.log, probe-print-sweep.log in the lead scratchpad, copied by Lattice):

| arm | tg128 |
|---|---|
| default | 30.7 to 32.6 |
| force-inline jdk.incubator.vector.*::* only | 30.6 |
| force-inline com.qxotic.jam.vector.*::* only (band kernel package) | 32.5 |
| force-inline com.qxotic.jinfer.kernels.*::* only | 84.9 |
| force-inline com.qxotic.jinfer.kernels.MatMul::* only | 88.6 |
| force-inline MatMul::blockFma ONLY | 90.9 |
| -XX:FreqInlineSize=2000 alone | 64.1 |
| -XX:InlineSmallCode=16000 alone | 31.4 |

The method: `MatMul.blockFma` (jinfer-kernels), 566 bytecodes, one Q8_0 block multiplied into the accumulator, three vector-width arms in one switch, called per block from `dotQ8Generic`, annotated with `com.oracle.svm.shared.AlwaysInline`, which the Graal compilers honour and C2 does not know. PrintInlining on the call site under C2: "hot method too big".

The rules, at source (openjdk/jdk tag jdk-25-ga, src/hotspot/share/opto/bytecodeInfo.cpp, InlineTree::should_inline and should_not_inline):
1. At a hot call site the callee may be up to `FreqInlineSize` bytecodes (325 on aarch64 and x86, cpu/*/c2_globals_*.hpp); larger is "hot method too big". blockFma is 566.
2. A callee that already has compiled code is declined when its machine code exceeds `InlineSmallCode` ("already compiled into a big method"); the effective default with tiered C2 is 2500 (compilerDefinitions.cpp resets the CPU header's 1000; Lattice's reading, 18036). A block of Vector API intrinsics compiles to more than that.
Platform is not the variable: both defaults are the same on x86. The author sees it on the Mac because the Mac runs the pure-Java matmul (no native library), so this method is the hot path.

Two patches on branch fixpoint/matmul-blockfma-inline (off upstream c7924d5d1):
- Split blockFma into blockFma512/256/128 (100/120/171/235 bytecodes) behind a constant-folding dispatcher: satisfies rule 1 only. Decode-only 30.1 and 31.0; with -XX:InlineSmallCode=16000 added, 86.4; with -XX:FreqInlineSize=2000 added, 57.8. PrintInlining then says blockFma128 "already compiled into a big method".
- Write the 256 and 128 block bodies out inside dotQ8Generic's loop (dotQ8Generic becomes 622 bytecodes, which is fine for a compile root; the helpers stay for the 512 path): satisfies both. No flags.

| | Corretto 25.0.2 decode-only | Corretto prefill then decode | GraalVM CE 25.4.4.1 decode-only |
|---|---|---|---|
| before (same jar lineage, 319d97e15) | 30.1, 31.0 | 404 / 32.3 | 39.1 |
| after (fixpoint/matmul-blockfma-inline) | 80.5, 81.1 | 379 / 79.3 | 101.2 |

jinfer-kernels module tests at the fix: 0 failures. The fix also removes GraalVM CE's decode-only slow state (39 to 101, against 108 in its prefill-first state), which says that state was the same helper not being inlined under CE's decode-only profile. Not re-measured: the sweep at all thread counts, Oracle GraalVM, JDK 26 and Temurin with the fix, x86, the other kernels that carry the same shape (FlashAttention qkTile 672 bytecodes, pvTile 457, accumulate 395, expRowInPlace 440, Ops.siluMultiplyInPlace 462; all reported "hot method too big" in the same log and are prefill-side).
