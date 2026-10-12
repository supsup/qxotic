package com.qxotic.jinfer.example.sqludf;

import java.util.List;

/**
 * The narrow seam between the SQL functions and the model: three operations, nothing of the engine
 * leaks through. {@link JinferBackend} is the real one; tests install a fake, so UDF registration
 * and dispatch are checked without a model file or a network.
 */
public interface LlmBackend extends AutoCloseable {

    /**
     * Maps every model now instead of on the first call that needs it, so a caller timing a query
     * can keep the load out of the clock. Idempotent; a fake has nothing to do.
     */
    void load();

    /** Free-form completion of {@code prompt}, at most {@code maxTokens} long. */
    String complete(String prompt, int maxTokens);

    /**
     * Exactly one of {@code labels} for {@code text}, under {@code instruction}. The real backend
     * makes any other reply unrepresentable (grammar-constrained decoding); a fake returns whatever
     * it likes, so a caller must still treat the result as data.
     */
    String choose(String instruction, String text, List<String> labels);

    /**
     * The embedding vector of {@code text}, as the embedding model emits it: the LFM2.5 embedder
     * L2-normalizes, another family may not, and {@code COSINE} normalizes anyway.
     */
    float[] embed(String text);

    /** Counters since the last {@link #resetStats}; a backend without timings reports zeros. */
    Stats stats();

    void resetStats();

    @Override
    void close();

    /**
     * Prompt/decode accounting over a run of calls - what a query over N rows cost the engine.
     * {@code restoredTokens} is the prefix the prompt cache served instead of recomputing: for a
     * classify query that is the instruction, restored for every row after the first.
     */
    record Stats(
            long calls,
            long promptTokens,
            long restoredTokens,
            long completionTokens,
            long promptNanos,
            long decodeNanos) {

        public static final Stats NONE = new Stats(0, 0, 0, 0, 0, 0);

        /** Prompt tokens actually computed per second (restored ones excluded), 0 when unknown. */
        public double promptTokensPerSecond() {
            return promptNanos == 0 ? 0 : (promptTokens - restoredTokens) / (promptNanos / 1e9);
        }

        /** Completion tokens decoded per second, 0 when unknown. */
        public double completionTokensPerSecond() {
            return decodeNanos == 0 ? 0 : completionTokens / (decodeNanos / 1e9);
        }

        public Stats plus(
                int promptTokens,
                int restoredTokens,
                int completionTokens,
                long prompt,
                long decode) {
            return new Stats(
                    calls + 1,
                    this.promptTokens + promptTokens,
                    this.restoredTokens + restoredTokens,
                    this.completionTokens + completionTokens,
                    promptNanos + prompt,
                    decodeNanos + decode);
        }
    }
}
