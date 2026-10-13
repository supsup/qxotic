# Benchmark results (fork-local)

Raw outputs of benchmark runs made on this fork, one directory per day.
This branch is never proposed upstream; feature branches quote numbers from here.

Every file names the branch and commit that was measured, the command, the machine and the JVM.
A number without those four is not a result.

## Machine

Apple M5 Max, 6 performance cores, 18 logical, 128 GB, macOS 27.
Runs here are under Amazon Corretto 25 (C2) with 128-bit NEON vectors and without the native matmul backend (cmake absent, `-Djam.native.skip=true`) unless a file says otherwise.
The engine labels this configuration `C2 (Vector API kernels de-intrinsified)` in the bench header and logs a SLOW_JIT cliff; both are set by which JIT is loaded (compiler identity), not by measurement.
Measured 2026-10-13 (fork-bench-results/2026-10-13/): prefill is the same under Corretto C2 and the GraalVM CE 25.4.4.1 JIT (within 4 percent at every thread count), decode is 2.4 to 2.5x faster under the Graal JIT (1 thread 32.8 vs 12.9 tok/s; 8 threads 165 vs 68), and the same GraalVM build with C2 forced (-XX:-UseJVMCICompiler) reproduces the Corretto numbers, so the gap is the compiler and not the JDK build. The SLOW_JIT label is therefore wrong about prefill and right about decode on this machine; it is still set by compiler identity, not by this measurement. GraalVM CE 25.0.0 is unusable for decode here (0.06 tok/s warm-up after a 71 tok/s prefill, killed after 65 minutes); only the 25.4 line carries the gain.
jam-native was not built (no cmake), so the jam backends are vector and scalar only.

## JVMs available

- Amazon Corretto 25.0.2 (the default)
- GraalVM CE 25.0.0 (`25-graalce`); measured 2026-10-13: decode pathological (0.06 tok/s), do not use for numbers
- GraalVM CE 25.4.4.1 (`25.4.4.1+1-graalce`), installed 2026-10-12; first comparison run 2026-10-13 (see Index)

## Tools

`fork-bench-results/tools/` holds what produces comparable runs across OS, JVM and jam backend.
Nothing there is a result.

| File | What |
|---|---|
| fork-bench-results/tools/matrix.md | the planned run matrix, the impossible cells and why, the command for each cell, how to read the numbers |
| fork-bench-results/tools/run-host.sh | one thread sweep on this host under a chosen JVM; builds nothing, downloads nothing, writes one headed result file |
| fork-bench-results/tools/run-container.sh | the same sweep on Linux aarch64 in Docker, for backend vector and backend native; builds both jars inside the container |
| fork-bench-results/tools/Dockerfile.linux-bench | the image for run-container.sh: a JDK chosen by build arg, plus cmake, a C toolchain, make, git and Maven |

Files written by the scripts start with one `# key value` line each for branch, commit, command, machine, jvm, jam backends and date (containers add container and os), instead of the single semicolon-separated line the first two files use.

## Index

| Date | File | Branch and commit | What |
|---|---|---|---|
| 2026-10-12 | fork-bench-results/2026-10-12/norm-peak-issue-7.txt | fixpoint/issue-6-7-kernels 17ba4702c vs upstream c7924d5d1 | rmsnorm and sumOfSquares, four accumulators against one |
| 2026-10-12 | fork-bench-results/2026-10-12/thread-sweep-lfm2.5-350m-q8_0.txt | fixpoint/bench-thread-sweep f6ab768fb | pp512 and tg128 at 1, 2, 4, 8 threads |
| 2026-10-13 | fork-bench-results/2026-10-13/thread-sweep-lfm2.5-350m-q8_0-macos-25.0.2-amzn-vector.txt | fixpoint/bench-thread-sweep 02ada9895 | cell H1: Corretto 25.0.2 C2, pp512 and tg128 at 1, 2, 4, 6, 8 threads |
| 2026-10-13 | fork-bench-results/2026-10-13/thread-sweep-lfm2.5-350m-q8_0-macos-25.4.4.1-1-graalce-vector.txt | fixpoint/bench-thread-sweep 02ada9895 | cell H2: GraalVM CE 25.4.4.1 Graal JIT, same sweep |
| 2026-10-13 | fork-bench-results/2026-10-13/thread-sweep-lfm2.5-350m-q8_0-macos-25-graalce-vector.txt | fixpoint/bench-thread-sweep 02ada9895 | cell H3: GraalVM CE 25.0.0, PARTIAL (killed; the file says FAILED exit 143): 1 thread pp 71 tok/s, decode did not finish warm-up |
| 2026-10-13 | fork-bench-results/2026-10-13/c2-control/thread-sweep-lfm2.5-350m-q8_0-macos-25.4.4.1-1-graalce-vector.txt | fixpoint/bench-thread-sweep 02ada9895 | cell D: the 25.4.4.1 build with -XX:-UseJVMCICompiler (C2); matches H1 |
| 2026-10-13 | fork-bench-results/2026-10-13/jvm-comparison.md | jfr-metrics 319d97e15 for the recorded runs | the comparison written up: the four cells, the JFR recordings, the intrinsics log and the tile and scalar controls |
