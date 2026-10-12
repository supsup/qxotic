# Benchmark results (fork-local)

Raw outputs of benchmark runs made on this fork, one directory per day.
This branch is never proposed upstream; feature branches quote numbers from here.

Every file names the branch and commit that was measured, the command, the machine and the JVM.
A number without those four is not a result.

## Machine

Apple M5 Max, 6 performance cores, 18 logical, 128 GB, macOS 27.
On this machine the JIT de-intrinsifies the Vector API kernels (the bench header says so and the engine logs a SLOW_JIT performance cliff).
The upstream author says Mac numbers are not representative yet; treat everything here as the slow path unless a file says otherwise.
jam-native was not built (no cmake), so the jam backends are vector and scalar only.

## JVMs available

- Amazon Corretto 25.0.2 (the default)
- GraalVM CE 25.0.0 (`25-graalce`)
- GraalVM CE 25.4.4.1 (`25.4.4.1+1-graalce`), installed 2026-10-12 for comparison runs; no run under it yet

## Index

| Date | File | Branch and commit | What |
|---|---|---|---|
| 2026-10-12 | fork-bench-results/2026-10-12/norm-peak-issue-7.txt | fixpoint/issue-6-7-kernels 17ba4702c vs upstream c7924d5d1 | rmsnorm and sumOfSquares, four accumulators against one |
| 2026-10-12 | fork-bench-results/2026-10-12/thread-sweep-lfm2.5-350m-q8_0.txt | fixpoint/bench-thread-sweep f6ab768fb | pp512 and tg128 at 1, 2, 4, 8 threads |
