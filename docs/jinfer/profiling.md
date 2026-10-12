---
sidebar_position: 4
---

# Profiling with JFR

**What the engine did, on the JVM's own timeline.**
jinfer emits [Java Flight Recorder](https://docs.oracle.com/en/java/javase/25/jfapi/) events for the phases that decide performance: model load, prompt tokenization, each prefill chunk, each decoded token, and each request.
They land in the same recording as the JDK's GC pauses, JIT compilation, allocation and CPU samples, so "what made this token slow" is a question the file answers.
No dependency: `jdk.jfr` is part of every JDK, and an event that nobody records costs a field write and a branch.

## Record

The CLI records a run with `--jfr`:

```bash
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -jar jinfer/jinfer-cli/target/jinfer.jar \
  --model LiquidAI/LFM2.5-350M-GGUF:Q8_0 --jfr run.jfr \
  instruct "List the planets." --temp 0 --max-output-tokens 64
```

The recording starts before the model loads and is written when the command returns, or when the JVM exits on a signal.
It holds the JDK's `profile` settings, jinfer's `jinfer.jfc` over them, and the per-token `jinfer.Decode` event switched on: a run that asks to be profiled wants its per-token latencies.
`--jfr` is a model option, so it works with `chat`, `instruct`, `server`, `speak` and `transcribe`.

From a checkout, `make jfr-run` records the golden prompt.
`MODEL=<ref or path>` selects the model and `FILE=<path>` the recording; the defaults are the `test-golden` model and `jinfer.jfr`.

Any JVM running jinfer records the same events with the JVM flag, per-token decode excepted:

```bash
java -XX:StartFlightRecording=settings=profile,settings=jinfer/jinfer-core/src/main/resources/jinfer.jfc,filename=run.jfr ...
```

Add `jinfer.Decode#enabled=true` to switch per-token events on there, or copy `jinfer.jfc` into `$JAVA_HOME/lib/jfr/` so `settings=jinfer` resolves by name.
`jinfer-bench` runs the same way: its prompt-processing tests (`pp512`, and the prefix replay of a depth test) emit one `jinfer.Prefill` per chunk, warm-up passes included; its token-generation loop calls the model directly and emits no `jinfer.Decode`, so the decode row of a bench recording comes from its `ChatEngine` metrics or reads `n/a`.
Start the recording after warm-up (`delay=`) or filter by time, or interpreter and C1 time is attributed to the kernels.

## Read

Two readers, coarse and fine:

```bash
make jfr-metrics FILE=run.jfr     # one table: throughput, latency percentiles, GC, allocation, CPU
make jfr-summary FILE=run.jfr     # jfr summary, then every jinfer.* event as the JDK's jfr tool prints it
```

`jfr-metrics` is `com.qxotic.jinfer.bench.JfrMetrics` in `jinfer-bench`, a reader over `jdk.jfr.consumer.RecordingFile` with no dependencies.
Its table has one row per question, in this shape (the numbers here are placeholders, not a measurement):

```text
| metric | value |
| ------ | ----- |
| recording | 4.12 s, 18342 events |
| model load | 0.61 s, 358.1 MB weights, lfm2, LFM2.5-350M-Q8_0.gguf |
| tokenizer | 1 prompts, 17 chars -> 24 tokens, 85.0 k chars/s |
| prefill | 24 tokens in 1 chunks, 1876.3 t/s |
| decode | 64 tokens, 241.9 t/s, step p50 4.0 ms, p95 4.6 ms, max 9.8 ms |
| time to first token | p50 31.2 ms, max 31.2 ms over 1 requests |
| gc | 2 collections, 3.1 ms paused, longest 2.0 ms |
| allocation | 48.2 MB, 11.7 MB/s (exact-delta, 14 threads) |
| cpu | jvm 71.3 % (user 69.8 %, system 1.5 %), machine 74.0 % |
```

Throughput comes from the per-chunk and per-token events when the recording has them, and from the per-request `jinfer.Inference` timings otherwise; a row whose events are absent reads `n/a` rather than zero.
The allocation row says where its number came from.
`jdk.ThreadAllocationStatistics` is a cumulative per-thread counter, so a thread read once says nothing about the recording; a thread read twice or more gives an exact delta.
`exact-delta` is the sum over threads when every thread seen was read at least twice, and `partial` names how many of them were, summing only those (a lower bound).
With no thread read twice the figure is the sum of the `jdk.ObjectAllocationSample` weights, marked `sampled, an estimate`.
With neither the row reads `unavailable`, never `0 B`.
Decode throughput here is tokens over the summed step durations, which is what `tg128` measures; the CLI's own `generation:` line divides by wall time from the prompt's end, which also counts the listener's work.

The JDK's `jfr` tool prints anything else:

```bash
jfr print --events jinfer.Decode run.jfr          # every decode step with its start time and duration
jfr print --events jdk.GarbageCollection run.jfr
jfr summary run.jfr
```

### JDK Mission Control

Open the file in [JDK Mission Control](https://jdk.java.net/jmc/): `jmc`, then File > Open File.
jinfer's events are under **Event Browser > jinfer**, grouped by category: Inference, Lifecycle, Memory, Performance.
Select `jinfer.Decode` for the histogram of step durations; drag a range in the timeline and the **Garbage Collections** and **Threads** pages show what else ran in it.
`jinfer.Inference` carries the per-request fields, so a slow request is one row to find and its `prefillTime`, `decodeTime` and `timeToFirstToken` say where the time went.

## The events

| event | one per | fields | on by default |
|-------|---------|--------|---------------|
| `jinfer.ModelLoad` | model load | model, architecture, maxContextLength, dimensions, weightsBytes, mapped | yes |
| `jinfer.Tokenize` | prompt encoded | chars, tokens | yes |
| `jinfer.Prefill` | prompt chunk ingested | tokens | yes |
| `jinfer.Decode` | decoded token | none: start time, duration and thread are the payload | no; `--jfr` turns it on |
| `jinfer.Inference` | request | model, operation, outputType, inputTokens, outputTokens, reasoningTokens, cachedTokens, cacheTier, queueTime, prefillTime, decodeTime, timeToFirstToken, finishReason, errorType | yes |
| `jinfer.Speculation` | speculative pass | draftedTokens, acceptedTokens, forwards | yes |
| `jinfer.MediaProjection` | projected image, audio or video | modality, source shape, errorType | yes |
| `jinfer.PromptCache`, `jinfer.MediaCache` | second, sampled | cache gauges; counters as deltas since the last sample | yes |
| `jinfer.Runtime` | chunk, sampled | vectorBits, threads | yes |
| `jinfer.Cliff` | performance cliff, once per run | cliff, detail | yes |

Every event also has JFR's own `startTime`, `duration` and `eventThread`.
What each one means:

- **ModelLoad**: the whole open, header parse to port load; its duration separates a slow load from a slow first token. `weightsBytes` is the tensor region, which is memory-mapped, so a cold run pages it in on the first tokens.
- **Tokenize**: the chat template rendered and encoded for one request, native codec or Jinja. `chars` over duration is tokenizer throughput; `tokens` is what the prompt cost the context.
- **Prefill**: one chunk of the prompt ingested, up to the batch capacity (`--batch-capacity`, default 512). `tokens` over duration is prompt-processing throughput for that chunk, the number `pp512` reports in aggregate; the chunks of a long prompt show whether ingestion slows as the context fills.
- **Decode**: one sampled token, from the logits projection through sampling and ingestion. Off by default because it is the one event whose count scales with output length.
- **Inference**: one request end to end. Its fields follow OpenTelemetry's `gen_ai.*` vocabulary where one exists; the Javadoc of `InferenceEvent` holds the mapping. `timeToFirstToken` is recorded rather than derived because it includes cache restore, prefill and the first sample.

Derived numbers:

- prompt-processing throughput: `Prefill.tokens / Prefill.duration` per chunk; over a request, `Inference.inputTokens - Inference.cachedTokens` over `Inference.prefillTime`
- decode throughput: `count(Decode) / sum(Decode.duration)`; per request, `Inference.outputTokens / Inference.decodeTime`
- tokenizer throughput: `Tokenize.chars / Tokenize.duration`

## Overhead

An event that is not being recorded is a field write or two and a branch; the per-token `Decode` event is resolved once per generation pass, not per token, so a process that never records pays nothing a benchmark can see.
With a recording on, the cost is the JDK's: the `profile` settings sample execution stacks every 10 ms and allocations by weight, which is a small, uniform tax on decode and invisible on prefill.
`Decode` itself is a timestamp pair per token, written to a thread-local buffer.
Measure it the way `test-golden` measures anything: the same greedy prompt with and without `--jfr`, and compare the `generation:` line; a reported number is never from a recorded run.

A recording buffers in memory and writes to disk in chunks; a long `server` run grows the file by some megabytes per minute of load, and `jfr print` on a large file takes a while.

What is deliberately not an event: per-token tokenizer decode (on the hot path; `TokenizeEvent` says why), a log probability per token (`DecodeEvent` says why), and a KV-cache gauge (`Telemetry` says why).

## JMH

The JMH suites (`json/benchmarks`, `toknroll/toknroll-benchmarks`) use JMH's own profiler, which writes one recording per fork:

```bash
make jfr-jmh                                                   # json-benchmarks under -prof jfr
java -jar json/benchmarks/target/json-benchmarks.jar -prof jfr:dir=bench-results
```

JMH 1.37, the version this reactor pins, ships the `jfr` profiler; `-lprof` lists the profilers the JDK in use supports.
`jinfer-bench` is not a JMH harness; record it with the JVM flag above.

## Native image

`jdk.jfr` works in a GraalVM native image only when the image is built with `--enable-monitoring=jfr`.
The `make native` recipe does not pass it, so `--jfr` on `bin/jinfer` fails with the JDK's "Flight Recorder is not available".
Profile on the JVM, or add the flag to a local native build.
