# jinfer developer conveniences - `make` (or `make help`) lists them. Builds are aliases into
# the ROOT Makefile (../Makefile), the single Maven entry point, because the jinfer subtree is
# not dependency-closed (gguf, json, toknroll, jota and jam are sibling trees only the root
# reactor sees on a clean clone); plain `mvn` inside this directory works only after a root
# `mvn install`. The poms are the source of truth: jinfer-parent's pluginManagement owns the
# shared shade + native-image configuration, jinfer-cli and jinfer-bench name their executables
# via the jinfer.image.* properties.

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)

JAVA ?= java
JAR_FILE = $(ROOT)/bin/jinfer.jar

# Command-line variables (MAVEN_FLAGS=-o, ...) ride MAKEFLAGS into the sub-make automatically.

default: help

##@ Build (aliases into the root Makefile)

jar: ## The CLI jar -> ../bin/jinfer.jar
	$(MAKE) -C $(ROOT) jinfer-jar

test: ## The jinfer subtree's tests (model-backed suites tag-gated, see pom.xml to opt in)
	$(MAKE) -C $(ROOT) jinfer-test

native: ## GraalVM native image of the CLI -> ../bin/jinfer; PRELOAD_GGUF=model.gguf embeds tokenizer metadata (the others: make -C jinfer-tts native, make -C jinfer-bench native)
	$(MAKE) -C $(ROOT) native PRELOAD_GGUF=$(PRELOAD_GGUF)

clean: ## Wipe the subtree's build output (and the sibling output its closure built), bin/ included
	$(MAKE) -C $(ROOT) jinfer-clean

##@ Run

# ./hotspot_compile_commands is a measurement instrument for C2's inliner, never a requirement
# (its header says how to measure with it); opt in with INLINE_HINTS=-XX:CompileCommandFile=...
INLINE_HINTS ?=
JAVA_RUNTIME_OPTIONS = --add-modules jdk.incubator.vector,jdk.httpserver \
	--enable-native-access=ALL-UNNAMED $(INLINE_HINTS)

run: jar ## Build, then launch the CLI: make run ARGS="chat --model hf.co/..."
	$(JAVA) $(JAVA_RUNTIME_OPTIONS) -jar $(JAR_FILE) $(ARGS)

run-jar-command: ## Print the full java invocation for running the jar by hand
	@echo $(JAVA) $(JAVA_RUNTIME_OPTIONS) -jar $(JAR_FILE)

# Two identical greedy runs off the jar must be byte-identical. MODEL is a reference, resolved as
# `jinfer pull` resolves it (the local store or the HuggingFace hub cache serve it offline, else it
# is downloaded), or a path to a GGUF.
MODEL ?= hf.co/LiquidAI/LFM2.5-8B-A1B-GGUF:Q8_0
GOLDEN_RUN = $(JAVA) $(JAVA_RUNTIME_OPTIONS) -jar $(JAR_FILE) instruct --model $(MODEL) "List the planets." --temp 0 --max-output-tokens 64

test-golden: jar ## Determinism check: two greedy runs, byte-identical (MODEL=ref or path)
	@$(GOLDEN_RUN) > /tmp/jinfer-golden-1.txt 2> /tmp/jinfer-golden-1.err || { cat /tmp/jinfer-golden-1.err >&2; exit 1; }
	@$(GOLDEN_RUN) > /tmp/jinfer-golden-2.txt 2> /tmp/jinfer-golden-2.err || { cat /tmp/jinfer-golden-2.err >&2; exit 1; }
	@diff /tmp/jinfer-golden-1.txt /tmp/jinfer-golden-2.txt && echo "test-golden: deterministic"

# jinfer-bench at each thread count in THREADS, one child process per count (the thread budget
# is fixed per process), one table per model: pp, tg, speedups, and the count from which decode
# stops scaling. The bench takes a GGUF path: GGUF=path/to/model.gguf, or MODEL=<reference>
# resolved through `jinfer pull` on an already-built CLI jar (make jar). One of the two must be
# given on the command line: test-golden's MODEL default is an 8B checkpoint, and a sweep that
# silently pulled it would be a long surprise. `pull` prints one path per file it holds, so the
# recipe keeps the GGUF line and refuses a reference that resolves to more than one. BENCH_ARGS
# passes anything else through (-p, -n, -r, -w, ...). The OOB flag is the one the README's
# reported numbers use. On a host without cmake: MAVEN_FLAGS=-Djam.native.skip=true.
BENCH_JAR = $(ROOT)/bin/jinfer-bench.jar
THREADS ?= 1,2,4,all
BENCH_ARGS ?= -p 512 -n 128 -r 5 -w 2
MODEL_GIVEN = $(filter command line environment,$(origin MODEL))

bench-sweep: bench-jar ## Thread sweep of pp/tg: THREADS=1,2,4,all with GGUF=model.gguf or MODEL=ref (needs bin/jinfer.jar), BENCH_ARGS="-r 5"
	@gguf="$(GGUF)"; \
	if [ -z "$$gguf" ]; then \
		if [ -z "$(MODEL_GIVEN)" ]; then \
			echo "bench-sweep: give GGUF=path/to/model.gguf or MODEL=<reference>" >&2; exit 2; \
		fi; \
		if [ ! -f "$(JAR_FILE)" ]; then \
			echo "bench-sweep: $(JAR_FILE) is missing and MODEL= needs it to pull; make jar" >&2; exit 2; \
		fi; \
		pulled=$$($(JAVA) $(JAVA_RUNTIME_OPTIONS) -jar $(JAR_FILE) pull $(MODEL)) || exit 1; \
		gguf=$$(printf '%s\n' "$$pulled" | grep -i '\.gguf$$'); \
		if [ "$$(printf '%s\n' "$$gguf" | grep -c .)" != "1" ]; then \
			echo "bench-sweep: $(MODEL) did not resolve to exactly one GGUF; pass GGUF=:" >&2; \
			printf '%s\n' "$$pulled" >&2; exit 2; \
		fi; \
	fi; \
	echo "bench-sweep: $$gguf threads=$(THREADS)" >&2; \
	$(JAVA) $(JAVA_RUNTIME_OPTIONS) -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0 -jar $(BENCH_JAR) \
		-m "$$gguf" $(BENCH_ARGS) --threads-sweep $(THREADS)

bench-jar: ## The bench jar -> ../bin/jinfer-bench.jar
	$(MAKE) -C $(ROOT) bench-jar

help: ## Show this help
	@awk 'BEGIN { FS = " *## *" } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
		NF >= 2 && $$1 ~ /^[a-zA-Z_-]+:/ { t = $$1; sub(/:.*/, "", t); \
			printf "  \033[36m%-16s\033[0m %s\n", t, $$2 }' $(MAKEFILE_LIST)

.PHONY: default help jar bench-jar test native clean run run-jar-command test-golden bench-sweep
