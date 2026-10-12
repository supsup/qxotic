package com.qxotic.jinfer.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code --threads-sweep}: the llama-bench tests (pp, tg) at every thread count in a list, one
 * table per model, with each count's speedup over the smallest and the count from which decode has
 * stopped scaling. The claim under test: decode is memory-bound and saturates at a few threads
 * while prefill scales with cores.
 *
 * <p>The thread budget is fixed for the life of a process: {@code RuntimeFlags.THREADS} is read
 * once at class initialization, {@code Parallel.shared()} is sized from it at ITS initialization,
 * and every jam backend runs on that pool ({@code JAM.Parallel.width()} is "fixed for a lifetime").
 * There is no supported path that resizes the pool, and the README's rule is already "one process
 * per configuration". So a sweep is one CHILD PROCESS per count: the same executable with the same
 * JVM flags, {@code -t N --no-capabilities} appended, its stderr (warmup progress) passed through
 * and its stdout table parsed. The child's {@code threads} column is checked against the requested
 * count, so a count that did not take effect fails the sweep instead of mislabeling a row.
 */
final class ThreadSweep {

    /** Decode counts as saturated from the smallest count whose tg is within this of the max. */
    static final double PLATEAU_TOLERANCE = 0.05;

    private static final double NaN = Double.NaN;

    /** One count's pp and tg means with their stddevs; {@code NaN} where the test was skipped. */
    record Point(int threads, double pp, double ppStddev, double tg, double tgStddev) {
        boolean hasPp() {
            return !Double.isNaN(pp);
        }

        boolean hasTg() {
            return !Double.isNaN(tg);
        }
    }

    private ThreadSweep() {}

    /**
     * The thread counts of a {@code --threads-sweep} list: comma-separated positive integers and
     * the word {@code all} (the engine's default, {@code engineDefault}), in ascending order with
     * duplicates dropped - {@code all} is usually one of the listed counts too.
     */
    static int[] parse(String list, int engineDefault) {
        if (list == null || list.isBlank()) {
            throw new IllegalArgumentException(
                    "--threads-sweep expects a comma-separated list of thread counts, e.g."
                            + " 1,2,4,all");
        }
        if (engineDefault < 1) {
            throw new IllegalArgumentException(
                    "engine default threads must be >= 1: " + engineDefault);
        }
        TreeSet<Integer> counts = new TreeSet<>();
        for (String item : list.split(",", -1)) {
            String text = item.strip();
            if (text.isEmpty()) {
                throw new IllegalArgumentException(
                        "--threads-sweep has an empty entry in '" + list + "'");
            }
            if (text.equals("all")) {
                counts.add(engineDefault);
                continue;
            }
            int count;
            try {
                count = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "--threads-sweep entry must be a positive integer or 'all', not '"
                                + text
                                + "'");
            }
            if (count < 1) {
                throw new IllegalArgumentException(
                        "--threads-sweep thread count must be >= 1, got: " + count);
            }
            counts.add(count);
        }
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The tables are keyed by the name a child prints, the model file's base name, so two {@code
     * -m} paths that differ only by directory would land in one table with their rows interleaved.
     * Refused here, before any child runs, rather than five minutes in.
     */
    static void requireDistinctNames(List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException(
                        "--threads-sweep keys its tables by model file name and two models are"
                                + " both named '"
                                + name
                                + "'; rename one");
            }
        }
    }

    /** The list as the header line shows it: {@code 1,2,4,8}. */
    static String label(int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int count : counts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(count);
        }
        return sb.toString();
    }

    /**
     * Runs every count as a child process and gathers the points per model, in list order. {@code
     * childArgs} is the bench's own command line without the sweep option; each child gets {@code
     * -t N --no-capabilities} appended.
     */
    static Map<String, List<Point>> run(List<String> childArgs, int[] counts)
            throws IOException, InterruptedException {
        List<String> launcher = launcher();
        Map<String, List<Point>> byModel = new LinkedHashMap<>();
        for (int count : counts) {
            List<String> command = new ArrayList<>(launcher);
            command.addAll(childArgs);
            command.add("--no-capabilities");
            command.add("-t");
            command.add(Integer.toString(count));
            System.err.printf("sweep: threads=%d%n", count);
            Process child =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
            child.getOutputStream().close();
            String stdout =
                    new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = child.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "sweep: child for threads=" + count + " exited " + exit + "\n" + stdout);
            }
            Map<String, Point> points = parseChild(stdout, count);
            if (points.isEmpty()) {
                throw new IllegalStateException(
                        "sweep: child for threads=" + count + " printed no pp/tg rows:\n" + stdout);
            }
            for (Map.Entry<String, Point> e : points.entrySet()) {
                byModel.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        return byModel;
    }

    /**
     * How to start another copy of this program: the native image itself, or the same JVM with the
     * same input arguments (module, native-access and -D flags) over the same classpath - as {@code
     * -jar} when this one was launched that way, so the jar's manifest keeps applying. Both carry
     * the engine's system properties ({@link #forwardedProperties}) as {@code -D} arguments: a
     * native image has no input-argument list, and a JVM run may have set one programmatically.
     */
    static List<String> launcher() {
        List<String> command = new ArrayList<>();
        String self =
                ProcessHandle.current()
                        .info()
                        .command()
                        .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(self);
        List<String> forwarded = forwardedProperties(System.getProperties());
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            command.addAll(forwarded); // a native image reads -D from its command line too
            return command;
        }
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(forwarded);
        String classPath = System.getProperty("java.class.path", "");
        String mainCommand = System.getProperty("sun.java.command", "");
        boolean launchedAsJar =
                classPath.endsWith(".jar")
                        && (mainCommand.equals(classPath)
                                || mainCommand.startsWith(classPath + " "));
        if (launchedAsJar) {
            command.add("-jar");
            command.add(classPath);
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(JinferBench.class.getName());
        }
        return command;
    }

    /** The property namespaces that decide what the engine measures. */
    private static final List<String> FORWARDED_PREFIXES =
            List.of("jinfer.", "jam.", "jdk.incubator.");

    /**
     * The {@code jinfer.*}, {@code jam.*} and {@code jdk.incubator.*} properties of {@code props}
     * as {@code -Dkey=value} arguments, sorted by key: what a child must see to measure the same
     * configuration as its parent (a disabled backend, a conv tile, the vector OOB check). {@code
     * jinfer.threads} is forwarded like the rest; the child's {@code -t} overrides it at run time.
     */
    static List<String> forwardedProperties(Properties props) {
        TreeSet<String> keys = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            for (String prefix : FORWARDED_PREFIXES) {
                if (key.startsWith(prefix)) keys.add(key);
            }
        }
        List<String> out = new ArrayList<>();
        for (String key : keys) out.add("-D" + key + "=" + props.getProperty(key));
        return out;
    }

    /**
     * A child's table row: {@code | model | threads | ppNNN | mean +- stddev |}. Decimals are
     * {@code Locale.ROOT} on both sides (the child's printTable, this class's cells), so a
     * comma-decimal default locale neither skips every row here nor prints one.
     */
    private static final Pattern ROW =
            Pattern.compile(
                    "^\\|\\s*(.*?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(pp|tg)(\\S*)\\s*\\|"
                            + "\\s*([0-9.]+)\\s*\\u00b1\\s*([0-9.]+)\\s*\\|\\s*$");

    /**
     * The pp/tg rows of one child's stdout as one point per model. The child's threads column is
     * {@code RuntimeFlags.THREADS} read back after {@code -t} set it ({@code
     * JinferBench.engineThreads}), not the parsed option echoed, so a row whose column is not
     * {@code threads} means the count did not reach the engine, and is a failure.
     */
    static Map<String, Point> parseChild(String stdout, int threads) {
        Map<String, double[]> cells = new LinkedHashMap<>();
        for (String line : stdout.split("\\R")) {
            Matcher m = ROW.matcher(line);
            if (!m.matches()) continue;
            String model = m.group(1);
            int reported = Integer.parseInt(m.group(2));
            if (reported != threads) {
                throw new IllegalStateException(
                        "sweep: asked for threads="
                                + threads
                                + " but the child ran "
                                + reported
                                + ": "
                                + line);
            }
            double[] c = cells.computeIfAbsent(model, k -> new double[] {NaN, NaN, NaN, NaN});
            int base = m.group(3).equals("pp") ? 0 : 2;
            c[base] = Double.parseDouble(m.group(5));
            c[base + 1] = Double.parseDouble(m.group(6));
        }
        Map<String, Point> points = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : cells.entrySet()) {
            double[] c = e.getValue();
            points.put(e.getKey(), new Point(threads, c[0], c[1], c[2], c[3]));
        }
        return points;
    }

    /**
     * The smallest count whose tg is within {@link #PLATEAU_TOLERANCE} of the best tg, or -1 when
     * no point measured tg.
     */
    static int plateau(List<Point> points) {
        double max = Double.NaN;
        for (Point p : points) {
            if (p.hasTg() && (Double.isNaN(max) || p.tg() > max)) max = p.tg();
        }
        if (Double.isNaN(max)) return -1;
        int from = -1;
        for (Point p : points) {
            if (!p.hasTg() || p.tg() < (1 - PLATEAU_TOLERANCE) * max) continue;
            if (from < 0 || p.threads() < from) from = p.threads();
        }
        return from;
    }

    /**
     * One model's table and its plateau line. Speedups are against the smallest count that measured
     * the test; a skipped test reads {@code n/a}.
     */
    static String table(String model, List<Point> points) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> Integer.compare(a.threads(), b.threads()));
        Point ppBase = null, tgBase = null;
        for (Point p : sorted) {
            if (ppBase == null && p.hasPp()) ppBase = p;
            if (tgBase == null && p.hasTg()) tgBase = p;
        }
        int w = Math.max(model.length(), "model".length());
        String fmt = "| %-" + w + "s | %7s | %16s | %16s | %10s | %10s |%n";
        StringBuilder out = new StringBuilder();
        out.append(
                String.format(
                        Locale.ROOT,
                        fmt,
                        "model",
                        "threads",
                        "pp t/s",
                        "tg t/s",
                        "tg speedup",
                        "pp speedup"));
        out.append(
                String.format(
                        Locale.ROOT,
                        fmt,
                        "-".repeat(w),
                        "------:",
                        "---------------:",
                        "---------------:",
                        "---------:",
                        "---------:"));
        for (Point p : sorted) {
            out.append(
                    String.format(
                            Locale.ROOT,
                            fmt,
                            model,
                            p.threads(),
                            p.hasPp() ? measurement(p.pp(), p.ppStddev()) : "n/a",
                            p.hasTg() ? measurement(p.tg(), p.tgStddev()) : "n/a",
                            p.hasTg() ? speedup(p.tg(), tgBase.tg()) : "n/a",
                            p.hasPp() ? speedup(p.pp(), ppBase.pp()) : "n/a"));
        }
        int from = plateau(sorted);
        if (from < 0) {
            out.append("plateau: n/a (tg not measured)").append(System.lineSeparator());
        } else {
            out.append(
                    String.format(
                            Locale.ROOT,
                            "plateau: tg within %d%% of max from %d threads",
                            Math.round(PLATEAU_TOLERANCE * 100),
                            from));
            // the top of the list is where the sweep stopped looking, not where tg stopped scaling
            if (from == sorted.get(sorted.size() - 1).threads()) {
                out.append(" (largest count: lower bound only)");
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }

    private static String measurement(double mean, double stddev) {
        return String.format(Locale.ROOT, "%.2f \u00b1 %.2f", mean, stddev);
    }

    private static String speedup(double value, double base) {
        return String.format(Locale.ROOT, "%.2fx", value / base);
    }
}
