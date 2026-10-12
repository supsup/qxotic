package com.qxotic.jinfer.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * The {@code --jfr <file>} recording: the JDK's {@code profile} settings plus jinfer's own ({@code
 * jinfer.jfc}), with the per-token {@code jinfer.Decode} event switched ON - a run that asks to be
 * profiled wants its per-token latencies. Started before the model loads, so the load is in the
 * recording; written to the file when the command returns, and on a JVM exit that bypasses it.
 *
 * <p>{@code -XX:StartFlightRecording=settings=profile,settings=<path to jinfer.jfc>,filename=...}
 * on the java command line records the same events, Decode excepted; this option exists so a
 * recording does not need the JVM flag, the settings path, or the jar's internals.
 */
final class Flight implements AutoCloseable {

    private final Recording recording;
    private final Path file;
    private final PrintStream err;

    private Flight(Recording recording, Path file, PrintStream err) {
        this.recording = recording;
        this.file = file;
        this.err = err;
    }

    /** A running recording bound for {@code file}; a null file is a no-op closeable. */
    static Flight start(Path file, PrintStream err) throws IOException {
        if (file == null) return new Flight(null, null, err);
        Recording recording = new Recording(settings());
        try {
            recording.setName("jinfer");
            recording.setToDisk(true);
            // an unwritable destination is refused HERE, before a model loads for nothing
            recording.setDestination(file);
            recording.setDumpOnExit(true); // a signal mid-run still leaves the file behind
            recording.start();
        } catch (IOException | RuntimeException e) {
            recording.close(); // a refused destination must not leak the recording
            throw e;
        }
        return new Flight(recording, file, err);
    }

    /** The JDK's profile, jinfer's events over it, and Decode on. Package-visible for its test. */
    static Map<String, String> settings() throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            settings.putAll(Configuration.getConfiguration("profile").getSettings());
            try (InputStream in = Flight.class.getResourceAsStream("/jinfer.jfc")) {
                if (in == null) throw new IOException("jinfer.jfc is not on the classpath");
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    settings.putAll(Configuration.create(reader).getSettings());
                }
            }
        } catch (ParseException e) {
            throw new IOException("cannot read the JFR settings: " + e.getMessage(), e);
        }
        settings.put("jinfer.Decode#enabled", "true");
        return settings;
    }

    /** Stops the recording, which writes it to the file, and says where it went. */
    @Override
    public void close() throws IOException {
        if (recording == null) return;
        try (recording) {
            recording.stop(); // the destination set above is written on stop
        }
        err.println("jfr: " + file);
    }
}
