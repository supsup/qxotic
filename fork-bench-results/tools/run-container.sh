#!/usr/bin/env bash
# run-container.sh - the thread sweep of run-host.sh, on Linux aarch64 inside a Docker container on
# this machine, once per jam backend (vector, then native).
#
# What it does, in order:
#   1. bind-mounts the worktree READ-ONLY at /src and copies it to /build/src inside the container,
#      so the build writes nothing to the host and never sees the host's cmake cache;
#   2. builds the bench jar twice from that copy: first with -Djam.native.skip=true (no libjam in
#      the jar: the same recipe as the host build), then without it (cmake builds libjam.so for
#      linux-aarch64, which upstream does not ship);
#   3. runs the same --threads-sweep for backend vector and for backend native;
#   4. writes one result file per backend into --out, headed like run-host.sh's plus
#      `# container` and `# os`.
#
# THE MODEL COMES FROM A NAMED DOCKER VOLUME, NOT A BIND MOUNT. The engine memory-maps the GGUF.
# A macOS folder shared into the Docker VM is served through a file-sharing layer (virtiofs or
# gRPC FUSE), so every page fault on the mapping crosses the VM boundary: load time and any decode
# that touches cold pages would measure the file share, not the engine. A named volume lives on
# the VM's own disk and is paged like any Linux file. One-time setup (docker cp -L follows the
# symlink a Hugging Face cache snapshot holds):
#
#   docker volume create qxotic-bench-models
#   docker container create --name qxotic-bench-copy -v qxotic-bench-models:/models qxotic-bench:temurin true
#   docker cp -L /path/to/LFM2.5-350M-Q8_0.gguf qxotic-bench-copy:/models/
#   docker rm qxotic-bench-copy
#
# NETWORK: the Maven build inside the container resolves plugins and dependencies from Maven
# Central. The local repository is the named volume qxotic-bench-m2 (created on first use), so
# only the first run downloads. Nothing else is fetched: no model, no source.
#
# Never --privileged, never the Docker socket. Containers are named qxotic-bench-* and run --rm.
#
# The image must already exist; this script does not build it (see Dockerfile.linux-bench).

set -euo pipefail

usage() {
    cat <<'USAGE'
usage: run-container.sh --jvm <temurin|graalce|...> --worktree <qxotic worktree>
                        --model <file name inside the model volume> --out <directory>
                        [--threads 1,2,4,6,8] [--cpus 6] [--memory 16g]
                        [--image <image ref>] [--model-volume qxotic-bench-models]

  --jvm           names the image: qxotic-bench:<jvm> unless --image is given. Also the JVM tag in
                  the result file name
  --worktree      a qxotic worktree (source only is enough; it is mounted read-only and rebuilt)
  --model         a .gguf file name relative to the root of the model volume
  --threads       comma-separated counts for --threads-sweep; 'all' = engine default
  --out           host directory for the result files (created if missing)
  --cpus          docker run --cpus, recorded in the result header (default 6)
  --memory        docker run --memory, recorded in the result header (default 16g)
  --image         image reference, overriding qxotic-bench:<jvm>
  --model-volume  named volume holding the model (default qxotic-bench-models)

environment:
  BENCH_ARGS  bench options other than -m and --threads-sweep (default: -p 512 -n 128 -r 5 -w 2)
  JVM_ARGS    extra JVM arguments, recorded in the command line (default: none)
USAGE
}

die() { echo "run-container.sh: $*" >&2; exit 2; }

jvm="" worktree="" model="" threads="1,2,4,6,8" out="" cpus="6" memory="16g"
image="" model_volume="qxotic-bench-models"
while [ $# -gt 0 ]; do
    case "$1" in
        --jvm)          [ $# -ge 2 ] || die "--jvm needs a value";          jvm="$2"; shift 2 ;;
        --worktree)     [ $# -ge 2 ] || die "--worktree needs a value";     worktree="$2"; shift 2 ;;
        --model)        [ $# -ge 2 ] || die "--model needs a value";        model="$2"; shift 2 ;;
        --threads)      [ $# -ge 2 ] || die "--threads needs a value";      threads="$2"; shift 2 ;;
        --out)          [ $# -ge 2 ] || die "--out needs a value";          out="$2"; shift 2 ;;
        --cpus)         [ $# -ge 2 ] || die "--cpus needs a value";         cpus="$2"; shift 2 ;;
        --memory)       [ $# -ge 2 ] || die "--memory needs a value";       memory="$2"; shift 2 ;;
        --image)        [ $# -ge 2 ] || die "--image needs a value";        image="$2"; shift 2 ;;
        --model-volume) [ $# -ge 2 ] || die "--model-volume needs a value"; model_volume="$2"; shift 2 ;;
        -h|--help)      usage; exit 0 ;;
        *)              usage >&2; die "unknown argument: $1" ;;
    esac
done
[ -n "$jvm" ] && [ -n "$worktree" ] && [ -n "$model" ] && [ -n "$out" ] || { usage >&2; exit 2; }

case "$jvm" in *[!A-Za-z0-9._-]*) die "--jvm must be a plain tag (letters, digits, . _ -): $jvm" ;; esac
case "$threads" in
    *[!0-9a-z,]*|"") die "--threads must be a comma-separated list of counts or 'all': $threads" ;;
esac
case "$cpus" in *[!0-9.]*|"") die "--cpus must be a number: $cpus" ;; esac
case "$memory" in *[!0-9a-zA-Z]*|"") die "--memory must look like 16g: $memory" ;; esac
case "$model" in
    /*|*..*) die "--model is a file name inside the volume $model_volume, not a host path: $model" ;;
esac
case "$model" in *.gguf|*.GGUF) ;; *) die "--model must be a .gguf file: $model" ;; esac
case "$model_volume" in
    */*|.*|"") die "--model-volume must be a NAMED volume, not a path (see the header comment): $model_volume" ;;
esac

[ -n "$image" ] || image="qxotic-bench:$jvm"

case "$(uname -m)" in
    arm64|aarch64) ;;
    *) die "this host is $(uname -m); the matrix is aarch64 only and an emulated run measures the emulator" ;;
esac

[ -d "$worktree" ] || die "--worktree is not a directory: $worktree"
worktree=$(cd "$worktree" && pwd)
[ -f "$worktree/Makefile" ] && [ -d "$worktree/jinfer/jinfer-bench" ] \
    || die "$worktree does not look like a qxotic worktree (no Makefile or jinfer/jinfer-bench)"

command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
if ! docker image inspect "$image" >/dev/null 2>&1; then
    here=$(cd "$(dirname "$0")" && pwd)
    echo "run-container.sh: image $image does not exist. Build it first (this script does not):" >&2
    echo "  docker build --platform linux/arm64 -f $here/Dockerfile.linux-bench -t $image $here" >&2
    echo "  (add --build-arg BASE_IMAGE=... for a JVM other than the default; see the Dockerfile)" >&2
    exit 2
fi
if ! docker volume inspect "$model_volume" >/dev/null 2>&1; then
    echo "run-container.sh: volume $model_volume does not exist. One-time setup:" >&2
    echo "  docker volume create $model_volume" >&2
    echo "  docker container create --name qxotic-bench-copy -v $model_volume:/models $image true" >&2
    echo "  docker cp -L /path/to/$model qxotic-bench-copy:/models/" >&2
    echo "  docker rm qxotic-bench-copy" >&2
    exit 2
fi

# ---- provenance, read on the host: the worktree's .git points at a host path the container
# cannot follow, so git never runs inside ----
branch=$(git -C "$worktree" rev-parse --abbrev-ref HEAD)
commit=$(git -C "$worktree" rev-parse --short=9 HEAD)
if [ -n "$(git -C "$worktree" status --porcelain --untracked-files=no)" ]; then
    commit="$commit (DIRTY tracked files: the container builds the working tree, not this commit)"
fi

host_machine=$(uname -srm)
if [ "$(uname -s)" = "Darwin" ]; then
    for key in hw.model hw.ncpu hw.perflevel0.physicalcpu hw.memsize; do
        host_machine="$host_machine; $key=$(sysctl -n "$key" 2>/dev/null || echo unknown)"
    done
fi

image_id=$(docker image inspect --format '{{.Id}}' "$image")
docker_version=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo unknown)

mkdir -p "$out"
out=$(cd "$out" && pwd)

name="qxotic-bench-$jvm-$(date -u '+%Y%m%dT%H%M%SZ')-$$"

set -- docker run --rm -i \
    --name "$name" \
    --platform linux/arm64 \
    --cpus "$cpus" \
    --memory "$memory" \
    --security-opt no-new-privileges \
    -v "$worktree:/src:ro" \
    -v "$model_volume:/models:ro" \
    -v "qxotic-bench-m2:/root/.m2" \
    -v "$out:/out" \
    -e "QB_BRANCH=$branch" \
    -e "QB_COMMIT=$commit" \
    -e "QB_HOST_MACHINE=$host_machine" \
    -e "QB_IMAGE=$image" \
    -e "QB_IMAGE_ID=$image_id" \
    -e "QB_DOCKER_VERSION=$docker_version" \
    -e "QB_CPUS=$cpus" \
    -e "QB_MEMORY=$memory" \
    -e "QB_JVM_TAG=$jvm" \
    -e "QB_MODEL=$model" \
    -e "QB_THREADS=$threads" \
    -e "QB_BENCH_ARGS=${BENCH_ARGS:--p 512 -n 128 -r 5 -w 2}" \
    -e "QB_JVM_ARGS=${JVM_ARGS:-}" \
    "$image" bash -s

echo "run-container.sh: $branch $commit" >&2
echo "run-container.sh: container $name, image $image, cpus $cpus, memory $memory" >&2

# Everything below the INNER marker runs inside the container. The heredoc is quoted: nothing in it
# is expanded on the host. The script arrives on the container's stdin (bash -s), so every child
# that might read stdin (Maven, java) gets </dev/null: on the first run Maven swallowed the rest of
# the script and bash exited 0 after the first build with nothing measured (2026-10-13).
"$@" <<'INNER'
set -euo pipefail

say() { echo "run-container.sh[in]: $*" >&2; }
slug() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9._\n' '-' | sed 's/--*/-/g; s/^-//; s/-$//'; }

model="/models/$QB_MODEL"
[ -f "$model" ] || { say "no $QB_MODEL in the model volume; it holds:"; ls -l /models >&2 || true; exit 2; }

# ---- 1. a container-local copy of the read-only source ----
# .git is a pointer to a host path; bin, node_modules and jam-native's build/ and dist/ are host
# build output (a cmake cache with host paths, a darwin libjam). Maven target/ directories are
# left to the `clean package` that bench-jar runs: a blanket target exclude would also drop the
# source directories of that name the repository keeps under src/.
say "copying /src to /build/src"
rm -rf /build/src /build/jars
mkdir -p /build/src /build/jars
tar -C /src \
    --exclude=./.git --exclude=./bin --exclude=./node_modules \
    --exclude=./jam/jam-native/build --exclude=./jam/jam-native/dist \
    -cf - . | tar -C /build/src -xf -

# ---- 2. two jars: without libjam, then with it ----
# Vector FIRST: jam-native/dist does not exist yet, so -Djam.native.skip=true has no pre-staged
# library to reuse and the jar carries the Java backends only.
say "building the bench jar with -Djam.native.skip=true"
make -C /build/src bench-jar MAVEN_FLAGS="-B -Djam.native.skip=true" </dev/null >&2
cp /build/src/bin/jinfer-bench.jar /build/jars/jinfer-bench-vector.jar

say "building the bench jar with jam-native (cmake)"
make -C /build/src bench-jar MAVEN_FLAGS="-B" </dev/null >&2
cp /build/src/bin/jinfer-bench.jar /build/jars/jinfer-bench-native.jar

# ---- 3. provenance that is only visible from inside ----
os_line=unknown
if [ -r /etc/os-release ]; then
    os_line=$(. /etc/os-release; echo "${PRETTY_NAME:-${NAME:-unknown} ${VERSION_ID:-}}")
fi
mem_total=$(awk '/^MemTotal:/ { print $2 " " $3 }' /proc/meminfo 2>/dev/null || echo unknown)
machine="$(uname -srm); nproc=$(nproc); MemTotal=$mem_total; host: $QB_HOST_MACHINE"
jvm_line=$(java -version 2>&1 | head -n 1)
model_tag=$(slug "$(basename "$QB_MODEL" | sed 's/\.[Gg][Gg][Uu][Ff]$//')")
jvm_tag=$(slug "$QB_JVM_TAG")

# ---- 4. one sweep per backend ----
failures=0
sweep() {
    backend="$1"; jar="$2"; backend_args="$3"

    # The options jinfer/Makefile's bench-sweep target uses. The sweep's child processes inherit
    # the parent's jam.* properties, so a disabled backend stays disabled in every child.
    # QB_BENCH_ARGS, QB_JVM_ARGS and backend_args are split on spaces on purpose.
    # shellcheck disable=SC2086
    set -- java \
        --add-modules jdk.incubator.vector,jdk.httpserver \
        --enable-native-access=ALL-UNNAMED \
        -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0 \
        $backend_args $QB_JVM_ARGS \
        -jar "$jar" -m "$model" $QB_BENCH_ARGS --threads-sweep "$QB_THREADS"
    command_line="$*"

    tmp=$(mktemp -d /tmp/qxotic-bench.XXXXXX)
    say "backend $backend: $command_line"
    status=0
    "$@" </dev/null 2>&1 >"$tmp/stdout" | tee "$tmp/stderr" >&2 || status=$?

    bench_header=$(grep -m 1 '^jinfer-bench ' "$tmp/stdout" || true)
    backends=$(printf '%s\n' "$bench_header" | awk -F' [|] ' '{ for (i = 1; i <= NF; i++) if ($i ~ /^jam: /) { sub(/^jam: /, "", $i); print $i } }')
    [ -n "$backends" ] || backends="unknown (no bench header line on stdout)"
    first=$(printf '%s' "$backends" | sed 's/[ ,(].*//')

    result="/out/thread-sweep-$model_tag-linux-$jvm_tag-$backend.txt"
    if [ -e "$result" ]; then
        result="/out/thread-sweep-$model_tag-linux-$jvm_tag-$backend-$(date -u '+%H%M%SZ').txt"
    fi

    {
        echo "# branch $QB_BRANCH"
        echo "# commit $QB_COMMIT"
        echo "# command $command_line"
        echo "# machine $machine"
        echo "# jvm $jvm_line"
        echo "# jam backends $backends"
        echo "# date $(date -u '+%Y-%m-%d %H:%MZ')"
        echo "# container image=$QB_IMAGE id=$QB_IMAGE_ID cpus=$QB_CPUS memory=$QB_MEMORY docker=$QB_DOCKER_VERSION"
        echo "# os $os_line"
        if grep -q 'perf cliff' "$tmp/stderr"; then
            grep 'perf cliff' "$tmp/stderr" | sort -u | sed 's/^/# cliff /'
        else
            echo "# cliff none reported on stderr"
        fi
        if [ "$status" -ne 0 ]; then
            echo "# FAILED exit status $status; what follows is partial"
        fi
        if [ "$first" != "$backend" ]; then
            echo "# MISMATCH wanted backend $backend, the bench header says the first backend is '$first': NOT a $backend result"
        fi
        cat "$tmp/stdout"
    } >"$result"
    say "wrote $result (host: the --out directory)"

    if [ "$status" -ne 0 ] || [ "$first" != "$backend" ]; then
        failures=$((failures + 1))
    fi
    rm -rf "$tmp"
}

# vector: the jar built without libjam, and jam.native.disabled as well (JAM.java reads
# jam.<provider id>.disabled), so the cell cannot silently become a native run.
sweep vector /build/jars/jinfer-bench-vector.jar "-Djam.native.disabled=true"
sweep native /build/jars/jinfer-bench-native.jar ""

if [ "$failures" -ne 0 ]; then
    say "$failures sweep(s) failed or measured the wrong backend; read the # FAILED / # MISMATCH lines"
    exit 1
fi
INNER
