package com.qxotic.jinfer.example.sqludf;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * What H2 binds. Each public static method is one SQL function; {@link #register} issues the {@code
 * CREATE ALIAS} statements. H2 maps the Java types itself: {@code String} is {@code VARCHAR},
 * {@code Float[]} is {@code REAL ARRAY}, {@code Boolean} is {@code BOOLEAN}, and a SQL {@code NULL}
 * argument arrives as a Java {@code null} - every function answers it with {@code NULL} rather than
 * asking the model about nothing.
 *
 * <p>The instruction prefix of a classify or judge call is a pure function of its label set (and
 * question), never of the row, so the prompt cache serves it for every row of a query.
 */
public final class LlmFunctions {

    static final String COMPLETE_MAX_TOKENS_PROPERTY = "jinfer.sqludf.maxTokens";
    static final int DEFAULT_COMPLETE_MAX_TOKENS = 128;

    private static final String CLASS = LlmFunctions.class.getName();

    /** Function name to the static method behind it, in registration order. */
    static final List<String[]> ALIASES =
            List.of(
                    new String[] {"LLM_COMPLETE", "complete"},
                    new String[] {"LLM_CLASSIFY", "classify"},
                    new String[] {"LLM_EMBED", "embed"},
                    new String[] {"COSINE", "cosine"},
                    new String[] {"LLM_JUDGE", "judge"});

    private LlmFunctions() {}

    /** Creates the five aliases on {@code connection} (replacing any earlier definition). */
    public static void register(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String[] alias : ALIASES) {
                statement.execute(
                        "CREATE ALIAS IF NOT EXISTS "
                                + alias[0]
                                + " FOR \""
                                + CLASS
                                + "."
                                + alias[1]
                                + "\"");
            }
        }
    }

    /** {@code LLM_COMPLETE(prompt VARCHAR) -> VARCHAR}: free-form, greedy, token-budgeted. */
    public static String complete(String prompt) {
        if (prompt == null) return null;
        int maxTokens =
                Integer.getInteger(COMPLETE_MAX_TOKENS_PROPERTY, DEFAULT_COMPLETE_MAX_TOKENS);
        return Llm.backend().complete(prompt, maxTokens);
    }

    /**
     * {@code LLM_CLASSIFY(text VARCHAR, labels VARCHAR) -> VARCHAR}: exactly one of the
     * comma-separated {@code labels}, enforced at the sampler. With {@code
     * jinfer.sqludf.shuffleLabels=true} the labels are listed in an order derived from the row's
     * text (deterministic per row, uniform over rows), so a model's preference for the first option
     * cannot favour one label across a whole table; the price is one cached prefix per permutation
     * instead of one per label set.
     */
    public static String classify(String text, String labels) {
        if (text == null || labels == null) return null;
        List<String> options = labels(labels);
        if (Llm.shuffleLabels()) {
            options = new ArrayList<>(options);
            Collections.shuffle(options, new Random(text.hashCode()));
        }
        return Llm.backend().choose(classifyInstruction(options), text, options);
    }

    /** {@code LLM_EMBED(text VARCHAR) -> REAL ARRAY}: the model's vector, as H2 boxes it. */
    public static Float[] embed(String text) {
        if (text == null) return null;
        float[] vector = Llm.backend().embed(text);
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) boxed[i] = vector[i];
        return boxed;
    }

    /**
     * {@code COSINE(a REAL ARRAY, b REAL ARRAY) -> DOUBLE}: cosine similarity, -1..1. Elements
     * arrive as whatever H2 boxed them to (Float for REAL, Double for a literal), hence Number.
     */
    public static Double cosine(Object[] a, Object[] b) {
        if (a == null || b == null) return null;
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vectors differ in length: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            double x = ((Number) a[i]).doubleValue(), y = ((Number) b[i]).doubleValue();
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / Math.sqrt(normA * normB);
    }

    /**
     * {@code LLM_JUDGE(text VARCHAR, question VARCHAR) -> BOOLEAN}: {@link #classify} over {@code
     * yes,no}, so the verdict is a real boolean, never a sentence that contains "yes".
     */
    public static Boolean judge(String text, String question) {
        if (text == null || question == null) return null;
        List<String> options = List.of("yes", "no");
        return "yes".equals(Llm.backend().choose(judgeInstruction(question), text, options));
    }

    /** The fixed prefix of every classify prompt for this label order. */
    static String classifyInstruction(List<String> labels) {
        return "Classify the text into exactly one of these categories: "
                + String.join(", ", labels)
                + ".\nReply with the category name only.";
    }

    /** The fixed prefix of every judge prompt for this question. */
    static String judgeInstruction(String question) {
        return "Answer the question about the text with yes or no.\nQuestion: "
                + question.strip()
                + "\nReply with yes or no only.";
    }

    /** Comma-separated to a de-duplicated list; blank entries are a caller error, not a label. */
    static List<String> labels(String csv) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String label = raw.strip();
            if (label.isEmpty()) {
                throw new IllegalArgumentException("blank label in '" + csv + "'");
            }
            labels.add(label);
        }
        if (labels.size() < 2) {
            throw new IllegalArgumentException(
                    "a classification needs at least two labels, got '" + csv + "'");
        }
        return List.copyOf(labels);
    }
}
