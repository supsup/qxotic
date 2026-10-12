#!/usr/bin/env bash
# run-host.sh - one thread sweep of jinfer-bench on THIS host, under a JVM chosen per run.
#
# Writes one result file headed by branch, commit, command, machine and JVM (the convention in
# fork-bench-results/README.md). Builds nothing and downloads nothing: the bench jar must already
# be in <worktree>/bin, and --model must be a GGUF file already on disk.
#
# Finding a model path without downloading: `jinfer pull <ref>` prints the path of a model it
# already holds; the Hugging Face cache is $HF_HUB_CACHE, else $HF_HOME/hub, else
# ~/.cache/huggingface/hub (models--<owner>--<repo>/snapshots/<rev>/<file>.gguf). This script
# never calls pull, because pull downloads what is missing.
#
# The JVM is selected by setting JAVA_HOME for the child process only. The sdkman default and the
# caller's shell are left alone.
#
# Written for the bash 3.2 that macOS ships: no associative arrays, no ${var,,}, no mapfile.

set -euo pipefail

usage() {
    cat <<'USAGE'
usage: run-host.sh --jvm <sdkman id | JAVA_HOME path> --worktree <built qxotic worktree>
                   --model <path/to/model.gguf> --out <directory> [--threads 1,2,4,6,8]

  --jvm       an sdkman candidate id (25.0.2-amzn, 25.4.4.1+1-graalce, ...) resolved under
              ${SDKMAN_DIR:-~/.sdkman}/candidates/java, or a JAVA_HOME directory
  --worktree  a qxotic worktree whose bin/jinfer-bench.jar is already built
  --model     a GGUF file (a path, not a model reference)
  --threads   comma-separated counts for --threads-sweep; 'all' = engine default (default 1,2,4,6,8)
  --out       directory for the result file (created if missing)

environment:
  BENCH_ARGS  bench options other than -m and --threads-sweep (default: -p 512 -n 128 -r 5 -w 2)
  JVM_ARGS    extra JVM arguments, recorded in the command line (default: none)
USAGE
}

die() { echo "run-host.sh: $*" >&2; exit 2; }

jvm="" worktree="" model="" threads="1,2,4,6,8" out=""
while [ $# -gt 0 ]; do
    case "$1" in
        --jvm)      [ $# -ge 2 ] || die "--jvm needs a value";      jvm="$2"; shift 2 ;;
        --worktree) [ $# -ge 2 ] || die "--worktree needs a value"; worktree="$2"; shift 2 ;;
        --model)    [ $# -ge 2 ] || die "--model needs a value";    model="$2"; shift 2 ;;
        --threads)  [ $# -ge 2 ] || die "--threads needs a value";  threads="$2"; shift 2 ;;
        --out)      [ $# -ge 2 ] || die "--out needs a value";      out="$2"; shift 2 ;;
        -h|--help)  usage; exit 0 ;;
        *)          usage >&2; die "unknown argument: $1" ;;
    esac
done
[ -n "$jvm" ] && [ -n "$worktree" ] && [ -n "$model" ] && [ -n "$out" ] || { usage >&2; exit 2; }

BENCH_ARGS="${BENCH_ARGS:--p 512 -n 128 -r 5 -w 2}"
JVM_ARGS="${JVM_ARGS:-}"

# ---- the JVM: a path if it names a directory, otherwise an sdkman candidate id ----
if [ -d "$jvm" ]; then
    jhome=$(cd "$jvm" && pwd)
else
    jhome="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$jvm"
fi
[ -x "$jhome/bin/java" ] || die "no java at $jhome/bin/java (from --jvm $jvm)"

# ---- the worktree and its jar ----
[ -d "$worktree" ] || die "--worktree is not a directory: $worktree"
worktree=$(cd "$worktree" && pwd)
jar="$worktree/bin/jinfer-bench.jar"
if [ ! -f "$jar" ]; then
    echo "run-host.sh: $jar is missing. Build it first (this script does not build):" >&2
    echo "  JAVA_HOME=$jhome make -C $worktree bench-jar MAVEN_FLAGS=-Djam.native.skip=true" >&2
    echo "(-Djam.native.skip=true because this host has no cmake; drop it on a host that has one)" >&2
    exit 2
fi

# ---- the model ----
[ -f "$model" ] || die "--model is not a file: $model (this script never downloads)"
case "$model" in *.gguf|*.GGUF) ;; *) die "--model must be a .gguf file: $model" ;; esac
model=$(cd "$(dirname "$model")" && pwd)/$(basename "$model")

case "$threads" in
    *[!0-9a-z,]*|"") die "--threads must be a comma-separated list of counts or 'all': $threads" ;;
esac

# ---- provenance ----
branch=$(git -C "$worktree" rev-parse --abbrev-ref HEAD)
commit=$(git -C "$worktree" rev-parse --short=9 HEAD)
if [ -n "$(git -C "$worktree" status --porcelain --untracked-files=no)" ]; then
    commit="$commit (DIRTY tracked files: the jar may not match this commit)"
fi
jar_built=$(date -u -r "$jar" '+%Y-%m-%d %H:%MZ')

machine=$(uname -srm)
if [ "$(uname -s)" = "Darwin" ]; then
    for key in hw.model hw.ncpu hw.perflevel0.physicalcpu hw.memsize; do
        machine="$machine; $key=$(sysctl -n "$key" 2>/dev/null || echo unknown)"
    done
fi

jvm_line=$(JAVA_HOME="$jhome" "$jhome/bin/java" -version 2>&1 | head -n 1)

# ---- names ----
slug() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9._\n' '-' | sed 's/--*/-/g; s/^-//; s/-$//'; }
model_tag=$(slug "$(basename "$model" | sed 's/\.[Gg][Gg][Uu][Ff]$//')")
jvm_tag=$(slug "$(basename "$jhome")")
case "$(uname -s)" in Darwin) os_tag=macos ;; *) os_tag=$(slug "$(uname -s)") ;; esac

mkdir -p "$out"
out=$(cd "$out" && pwd)

# The options jinfer/Makefile's bench-sweep target uses: JAVA_RUNTIME_OPTIONS plus the OOB flag the
# bench README's reported numbers use. BENCH_ARGS and JVM_ARGS are split on spaces on purpose.
# shellcheck disable=SC2086
set -- "$jhome/bin/java" \
    --add-modules jdk.incubator.vector,jdk.httpserver \
    --enable-native-access=ALL-UNNAMED \
    -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0 \
    $JVM_ARGS \
    -jar "$jar" -m "$model" $BENCH_ARGS --threads-sweep "$threads"
command_line="JAVA_HOME=$jhome $*"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/qxotic-bench.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

echo "run-host.sh: $branch $commit" >&2
echo "run-host.sh: $jvm_line" >&2
echo "run-host.sh: $command_line" >&2

# stdout (the header line and the table) is the result; stderr (warm-up progress and the
# `perf cliff [NAME]` lines) is shown live and kept so the cliffs can be recorded.
status=0
JAVA_HOME="$jhome" "$@" 2>&1 >"$tmp/stdout" | tee "$tmp/stderr" >&2 || status=$?

bench_header=$(grep -m 1 '^jinfer-bench ' "$tmp/stdout" || true)
# "jinfer-bench V | runtime: R | jam: native, vector, scalar | threads: ..." -> the jam field
backends=$(printf '%s\n' "$bench_header" | awk -F' [|] ' '{ for (i = 1; i <= NF; i++) if ($i ~ /^jam: /) { sub(/^jam: /, "", $i); print $i } }')
[ -n "$backends" ] || backends="unknown (no bench header line on stdout)"
# The file is named after the backend matmuls try FIRST, which is what the run measured.
backend_tag=$(slug "$(printf '%s' "$backends" | sed 's/[ ,(].*//')")
[ -n "$backend_tag" ] || backend_tag=unknown

result="$out/thread-sweep-$model_tag-$os_tag-$jvm_tag-$backend_tag.txt"
if [ -e "$result" ]; then
    # A result is a record: never overwrite one. Keep both and let a human choose.
    result="$out/thread-sweep-$model_tag-$os_tag-$jvm_tag-$backend_tag-$(date -u '+%H%M%SZ').txt"
fi

{
    echo "# branch $branch"
    echo "# commit $commit"
    echo "# command $command_line"
    echo "# machine $machine"
    echo "# jvm $jvm_line"
    echo "# jam backends $backends"
    echo "# date $(date -u '+%Y-%m-%d %H:%MZ')"
    echo "# jar $jar built $jar_built"
    if grep -q 'perf cliff' "$tmp/stderr"; then
        grep 'perf cliff' "$tmp/stderr" | sort -u | sed 's/^/# cliff /'
    else
        echo "# cliff none reported on stderr"
    fi
    [ "$status" -eq 0 ] || echo "# FAILED exit status $status; what follows is partial"
    cat "$tmp/stdout"
} >"$result"

echo "run-host.sh: wrote $result" >&2
exit "$status"
