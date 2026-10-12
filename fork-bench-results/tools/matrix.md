# Planned run matrix: OS x JVM x jam backend

One machine: Apple M5 Max, 6 performance cores, 18 logical, 128 GB.
Nothing in this file is a result. It is the plan, the commands, and what each comparison is for.
Results go in `fork-bench-results/<date>/`, one file per cell, written by the two scripts here.

## The two questions

1. Is there a `SLOW_JIT` cliff on this machine at all, and does anything change under the newer GraalVM, or on Linux?
   The bench header prints `runtime: C2 (Vector API kernels de-intrinsified)` on the runs made so far, a label set by which JIT is loaded, not a measured ratio.
   No Corretto-versus-GraalVM comparison has been run on this machine yet, and the upstream author's own benchmark page for his speech model says C2 is not the cliff it is documented to be.
2. Does decode (`tg`) plateau at 2 to 4 threads in the cells with the most per-core throughput?
   The upstream bench README says "Decode is memory-bound at high thread counts".
   The one existing sweep (Corretto 25 C2, 128-bit NEON, vector backend, no native matmul) shows no plateau up to 8 threads: `tg` scaled 5.37x.

## Read this before trusting the header line

The `runtime:` field is a classification, not a measurement.
`Segments.vectorJitDetected()` reads which JIT compiler is active (the `UseJVMCICompiler` VM option) and decides from that alone.
So the header is expected to say `C2 (...)` on every HotSpot C2 JVM (Corretto, Temurin) on either OS, and `GraalVM JIT` on every GraalVM CE where Graal is the default compiler, whatever the version.
The header changing between cells therefore proves only that the compiler changed.
Whether the cliff is real on a given cell is answered by the numbers: `pp` and `tg` under Graal against C2, same OS, same backend, same thread count.
Each result file also records the `perf cliff [NAME]` lines the engine printed on stderr (`# cliff ...`).

## Axes

| Axis | Values | Notes |
|---|---|---|
| OS | macOS host; Linux aarch64 in a Docker container on the same machine | the container runs in Docker's Linux VM, so it also changes the kernel, the scheduler and who owns the cores |
| JVM (host) | Amazon Corretto 25.0.2 (`25.0.2-amzn`); GraalVM CE 25.4.4.1 (`25.4.4.1+1-graalce`) | GraalVM CE 25.0.0 (`25-graalce`) is also installed: an optional third cell that separates "newer GraalVM" from "any GraalVM" |
| JVM (container) | Eclipse Temurin 25 (`eclipse-temurin:25-jdk`); GraalVM CE 25 (`ghcr.io/graalvm/jdk-community:25`, tag to confirm at build time) | NOT the same builds as the host's. Temurin and Corretto are both HotSpot with C2, but they are different builds; the GraalVM image is whatever the tag resolves to, not necessarily 25.4.4.1. The `# jvm` line of each file is the authority |
| jam backend | `vector` (Java Vector API); `native` (C, `libjam`, built by cmake) | the header's `jam:` field lists what loaded, in the order matmuls try them |
| Model | LFM2.5-350M Q8_0 (`LFM2.5-350M-Q8_0.gguf`) | the only model measured so far. A larger model is worth one extra row per OS once these cells exist, because a memory-bound plateau depends on how far the weights exceed the caches; none is chosen here |

## Cells

`pp512` and `tg128`, `-r 5 -w 2`, threads 1,2,4,6,8, model LFM2.5-350M Q8_0 in every cell.

| Cell | OS | JVM | Backend | Possible | Script |
|---|---|---|---|---|---|
| H1 | macOS | Corretto 25.0.2 | vector | yes (re-measures the existing run with 6 added) | run-host.sh |
| H2 | macOS | GraalVM CE 25.4.4.1 | vector | yes | run-host.sh |
| H3 | macOS | GraalVM CE 25.0.0 | vector | yes, optional | run-host.sh |
| H4 | macOS | Corretto 25.0.2 | native | NO: the host has no cmake, so `libjam.dylib` cannot be built | - |
| H5 | macOS | GraalVM CE 25.4.4.1 | native | NO: same reason | - |
| L1 | Linux aarch64 | Temurin 25 | vector | yes | run-container.sh (one invocation covers L1 and L2) |
| L2 | Linux aarch64 | Temurin 25 | native | yes | same invocation as L1 |
| L3 | Linux aarch64 | GraalVM CE 25 | vector | yes, once the image tag is confirmed | run-container.sh (one invocation covers L3 and L4) |
| L4 | Linux aarch64 | GraalVM CE 25 | native | yes, once the image tag is confirmed | same invocation as L3 |
| X | any x86-64 | any | any | NO: this machine is aarch64. An x86 container here would run under emulation, and an emulated number measures the emulator, not the engine or the JIT. run-container.sh refuses a non-aarch64 host and pins `--platform linux/arm64` | - |

Why H4 and H5 stay impossible rather than "install cmake": that is a change to the host, not a benchmark step, and it is not this plan's to make.
If cmake is installed later, build without `MAVEN_FLAGS=-Djam.native.skip=true`; run-host.sh then names the file after whatever backend the header lists first.

## Commands

Set these once. `WT` is the worktree that has `--threads-sweep` (branch fixpoint/bench-thread-sweep).

```bash
WT=/Users/charles/projects/commander-worktrees/fixpoint/qxotic-worktree/bench-thread-sweep
TOOLS=/Users/charles/projects/commander-worktrees/fixpoint/qxotic-worktree/bench-results/fork-bench-results/tools
OUT=/Users/charles/projects/commander-worktrees/fixpoint/qxotic-worktree/bench-results/fork-bench-results/$(date -u +%Y-%m-%d)
MODEL=/path/to/LFM2.5-350M-Q8_0.gguf     # a file already on disk; neither script downloads
```

### One-time setup

Host jar (already present in the worktree as of this writing; rebuild after any source change).
One jar serves H1, H2 and H3: the JVM under test only runs it.

```bash
make -C "$WT" bench-jar MAVEN_FLAGS=-Djam.native.skip=true
```

Container images (network needed: base image, packages, Maven):

```bash
docker build --platform linux/arm64 -f "$TOOLS/Dockerfile.linux-bench" -t qxotic-bench:temurin "$TOOLS"
docker build --platform linux/arm64 -f "$TOOLS/Dockerfile.linux-bench" \
    --build-arg BASE_IMAGE=ghcr.io/graalvm/jdk-community:25 -t qxotic-bench:graalce "$TOOLS"
```

Model volume (a named volume, not a shared folder; run-container.sh explains why):

```bash
docker volume create qxotic-bench-models
docker container create --name qxotic-bench-copy -v qxotic-bench-models:/models qxotic-bench:temurin true
docker cp -L "$MODEL" qxotic-bench-copy:/models/
docker rm qxotic-bench-copy
```

### Per cell

```bash
# H1  macOS, Corretto 25.0.2, vector
"$TOOLS/run-host.sh" --jvm 25.0.2-amzn --worktree "$WT" --model "$MODEL" --threads 1,2,4,6,8 --out "$OUT"

# H2  macOS, GraalVM CE 25.4.4.1, vector
"$TOOLS/run-host.sh" --jvm 25.4.4.1+1-graalce --worktree "$WT" --model "$MODEL" --threads 1,2,4,6,8 --out "$OUT"

# H3  macOS, GraalVM CE 25.0.0, vector (optional)
"$TOOLS/run-host.sh" --jvm 25-graalce --worktree "$WT" --model "$MODEL" --threads 1,2,4,6,8 --out "$OUT"

# L1 + L2  Linux aarch64, Temurin 25, vector then native
"$TOOLS/run-container.sh" --jvm temurin --worktree "$WT" --model LFM2.5-350M-Q8_0.gguf \
    --threads 1,2,4,6,8 --cpus 8 --memory 16g --out "$OUT"

# L3 + L4  Linux aarch64, GraalVM CE 25, vector then native
"$TOOLS/run-container.sh" --jvm graalce --worktree "$WT" --model LFM2.5-350M-Q8_0.gguf \
    --threads 1,2,4,6,8 --cpus 8 --memory 16g --out "$OUT"
```

`--cpus 8` because the largest thread count is 8: a quota below the thread count throttles the extra threads and would look like a plateau.
`--cpus` is a CPU-time quota, not a pin.
The container cannot choose performance cores; the macOS scheduler places the VM's vCPUs.
Check Docker's own VM limits (CPUs and memory) before the first run: they cap what `--cpus` and `--memory` can mean, and each file records `nproc` and `MemTotal` as seen inside.

A C2 control on a GraalVM JDK, if the Graal cells need one (same JDK build, only the compiler changes; the bench README names the flag):

```bash
JVM_ARGS=-XX:-UseJVMCICompiler "$TOOLS/run-host.sh" --jvm 25.4.4.1+1-graalce --worktree "$WT" --model "$MODEL" --out "$OUT/c2-control"
```

It goes to its own directory because the file name does not encode `JVM_ARGS` (the `# command` line does).

## Expected wall time

The one measurement: the existing 1,2,4,8 sweep of LFM2.5-350M Q8_0 on Corretto took about five minutes, so about 75 seconds per thread count including JVM start, model load and adaptive warm-up.
Everything below is that figure scaled, not measured.

| Cell | Sweeps | Estimate |
|---|---|---|
| H1 | 1 x 5 counts | about 6 to 7 minutes |
| H2, H3 | 1 x 5 counts each | at most the same; less if Graal is faster, since the token counts are fixed |
| L1 + L2 | 2 x 5 counts | about 13 minutes of sweeps, plus two Maven builds and one cmake build of unknown length (the first run also downloads the Maven dependencies) |
| L3 + L4 | 2 x 5 counts | as L1 + L2; the Maven repository volume is warm by then |

The whole matrix is under an hour of measuring if the builds are quick.
Run it on mains power with the machine idle; do not run two cells at once.

## What each comparison shows

| Compare | Held fixed | Shows |
|---|---|---|
| H1 vs H2 | macOS, vector, jar | whether GraalVM CE 25.4.4.1 lifts `pp` and `tg` over C2 on Apple Silicon, i.e. whether the cliff is real here and whether this JVM avoids it |
| H2 vs H3 | macOS, vector, jar, Graal JIT | whether the newer GraalVM matters, or any GraalVM CE 25 does |
| H1 vs L1 | C2 (different vendor builds), vector | whether the OS matters under C2. The header should say C2 on both |
| H2 vs L3 | Graal JIT (versions may differ), vector | whether the OS matters under the Graal JIT |
| L1 vs L2, L3 vs L4 | OS, JVM, container | Java Vector API kernels against the C kernels, per JIT. If H1 vs H2 shows a real cliff under C2, the C2 gap includes its cost for prefill; otherwise both are the honest Java-against-C gap |
| L2 vs L4 | OS, native backend | how much of the engine outside the matmuls still depends on the JIT |
| `tg` across threads in H2, L3, L4 | everything but threads | the plateau question. These are the cells not carrying the `C2 (...)` label (L2 has native matmuls but a C2 engine around them); whether that label marks a real difference is what H1 vs H2 tests |

Every cross-OS comparison also crosses a VM boundary and a JDK build.
A difference there is a lead to chase, not a conclusion.

## How to read it

- Prefill (`pp`) is compute-bound and batched: it should scale with threads, close to linearly up to the 6 performance cores. Past 6 the extra threads land on efficiency cores, so expect less than proportional gains from 6 to 8.
- Decode (`tg`) is one token at a time over the whole weight set. If it is memory-bound, `tg` stops rising at a low thread count while `pp` keeps rising. That divergence is the signature the README describes.
- Low enough per-core throughput makes both compute-bound: memory bandwidth is never the limit, and `tg` scales like `pp`. The existing C2 sweep shows this shape (`tg` 5.37x and `pp` 5.39x at 8 threads). A plausible reading is that per-core throughput on 128-bit vectors without the native backend is low enough that eight cores do not saturate this machine's memory bandwidth; this is an inference, and the Corretto-versus-GraalVM and native-backend cells here are the test. So a `tg` curve that tracks `pp` is consistent with low per-core throughput, whatever its cause, and is not by itself evidence against the README.
- The `plateau:` line names the smallest count whose `tg` is within 5% of the best. `(largest count: lower bound only)` means the sweep found no plateau.
- Read the standard deviation before reading a difference. Two cells whose intervals overlap have not been told apart.
- Check `# jam backends` and `# cliff` in every file before comparing it with another. A `# MISMATCH` or `# FAILED` line means the file is not the cell its name says.

## Not covered

- The NormPeak microbench (`com.qxotic.jinfer.bench.NormPeak` on branch fixpoint/issue-6-7-kernels, run with `-cp bin/jinfer-bench.jar`) is a kernel loop without a model. These scripts run only the thread sweep. It prints `vectorJit=slow|fast` itself (the same compiler-identity label, not a measurement), so running it under Corretto and under GraalVM is a cheap second witness for question 1.
- The native image (`runtime: native image`) is a third compiler; it is not in this matrix.
- The bench README's A, B, B, A ordering rule for two-way comparisons. Each script measures once; repeat a cell (the scripts never overwrite a result file) when a difference is small enough to need it.
