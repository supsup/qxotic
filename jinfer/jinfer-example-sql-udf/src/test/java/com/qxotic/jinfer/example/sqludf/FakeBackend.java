package com.qxotic.jinfer.example.sqludf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A backend with no model: answers are rules over the text, every call is recorded. {@code choose}
 * returns the first label that occurs in the text (case-insensitive), else the first label offered;
 * {@code embed} is a deterministic function of the text; {@code complete} echoes.
 */
final class FakeBackend implements LlmBackend {

    record Choice(String instruction, String text, List<String> labels) {}

    final List<Choice> choices = new ArrayList<>();
    final List<String> embedded = new ArrayList<>();
    final List<String> completed = new ArrayList<>();
    int lastMaxTokens;
    boolean closed;

    @Override
    public void load() {}

    @Override
    public String complete(String prompt, int maxTokens) {
        completed.add(prompt);
        lastMaxTokens = maxTokens;
        return "echo: " + prompt;
    }

    @Override
    public String choose(String instruction, String text, List<String> labels) {
        choices.add(new Choice(instruction, text, List.copyOf(labels)));
        String lower = text.toLowerCase(Locale.ROOT);
        // judge calls offer yes,no: "yes" iff the text talks about a refund
        if (labels.equals(List.of("yes", "no"))) {
            return lower.contains("refund") ? "yes" : "no";
        }
        for (String label : labels) {
            if (lower.contains(label.toLowerCase(Locale.ROOT))) return label;
        }
        return labels.get(0);
    }

    @Override
    public float[] embed(String text) {
        embedded.add(text);
        float[] v = new float[3];
        for (char c : text.toCharArray()) {
            if (c == 'a' || c == 'A') v[0]++;
            else if (c == 'e' || c == 'E') v[1]++;
            else v[2]++;
        }
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        return v;
    }

    @Override
    public Stats stats() {
        return Stats.NONE;
    }

    @Override
    public void resetStats() {}

    @Override
    public void close() {
        closed = true;
    }
}
