package com.qxotic.jinfer.example.sqludf;

import com.qxotic.jinfer.hub.ModelStore;
import java.nio.file.Path;
import java.util.List;

/**
 * One engine per JVM. H2 calls the UDFs as static methods, so the model has to be found from static
 * scope: a lazy getter opens it on the first call that needs it, and a shutdown hook frees it.
 *
 * <p>Knobs are system properties, read once at load:
 *
 * <ul>
 *   <li>{@code jinfer.sqludf.model}: the chat GGUF, as a model reference or a path (default {@value
 *       #DEFAULT_MODEL})
 *   <li>{@code jinfer.sqludf.embedModel}: the embedding GGUF (default {@value
 *       #DEFAULT_EMBED_MODEL})
 *   <li>{@code jinfer.sqludf.contextCapacity}: positions per engine state (default {@value
 *       #DEFAULT_CONTEXT_CAPACITY})
 *   <li>{@code jinfer.sqludf.shuffleLabels}: present the labels in a per-row order, see {@link
 *       LlmFunctions#classify}
 * </ul>
 *
 * A reference is resolved through {@link ModelStore#standard()}: the local cache first, else a
 * download. {@link #install} replaces the engine for a test (a fake, or a backend built from a
 * cached fixture).
 */
public final class Llm {

    public static final String MODEL_PROPERTY = "jinfer.sqludf.model";
    public static final String DEFAULT_MODEL = "LiquidAI/LFM2.5-350M-GGUF:Q8_0";
    public static final String EMBED_MODEL_PROPERTY = "jinfer.sqludf.embedModel";
    public static final String DEFAULT_EMBED_MODEL = "LiquidAI/LFM2.5-Embedding-350M-GGUF:Q8_0";
    public static final String CONTEXT_CAPACITY_PROPERTY = "jinfer.sqludf.contextCapacity";
    public static final int DEFAULT_CONTEXT_CAPACITY = 2048;
    public static final String SHUFFLE_LABELS_PROPERTY = "jinfer.sqludf.shuffleLabels";

    private static volatile LlmBackend installed;
    private static LlmBackend engine; // the per-JVM engine, under the class lock
    private static Throwable loadFailure; // why opening it failed, kept so every later call says

    private Llm() {}

    /** The backend the functions dispatch to: an installed one, else the per-JVM engine. */
    public static LlmBackend backend() {
        LlmBackend override = installed;
        return override != null ? override : engine();
    }

    /**
     * The per-JVM engine, opened on the first call that needs it: registering the aliases must not
     * map weights. A failed open (offline, a bad reference) is remembered and rethrown with its
     * original message on every later call. A class-initialization holder would instead report it
     * once as {@code ExceptionInInitializerError} and forever after as {@code
     * NoClassDefFoundError} with the cause gone.
     */
    private static synchronized LlmBackend engine() {
        if (engine != null) return engine;
        if (loadFailure != null) {
            throw new IllegalStateException(
                    "the engine failed to open earlier: " + loadFailure, loadFailure);
        }
        try {
            engine = open();
            return engine;
        } catch (RuntimeException | Error e) {
            loadFailure = e;
            throw e;
        }
    }

    private static LlmBackend open() {
        String chat = System.getProperty(MODEL_PROPERTY, DEFAULT_MODEL);
        String embed = System.getProperty(EMBED_MODEL_PROPERTY, DEFAULT_EMBED_MODEL);
        int capacity = Integer.getInteger(CONTEXT_CAPACITY_PROPERTY, DEFAULT_CONTEXT_CAPACITY);
        // both references at once: a cold start downloads the pair concurrently
        List<Path> paths = ModelStore.standard().resolveAll(List.of(chat, embed));
        JinferBackend backend = new JinferBackend(paths.get(0), paths.get(1), capacity);
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close, "jinfer-sqludf-close"));
        return backend;
    }

    /**
     * Routes every function to {@code backend} until called again with {@code null}. The caller
     * owns the installed backend's lifetime; the per-JVM engine is untouched (and never opened if
     * nothing else asks for it).
     */
    public static void install(LlmBackend backend) {
        installed = backend;
    }

    /** Whether labels are presented in a per-row order (the bias mitigation, see the README). */
    static boolean shuffleLabels() {
        return Boolean.getBoolean(SHUFFLE_LABELS_PROPERTY);
    }
}
