package com.qxotic.jinfer.example.sqludf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qxotic.jinfer.testkit.TestModels;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The functions over the real engine: LFM2.5-350M classifies and judges, LFM2.5-Embedding-350M
 * embeds. Model-gated via {@link TestModels} (lookup only, never a download). Run: {@code mvn
 * -Pexamples -pl jinfer/jinfer-example-sql-udf test -Dsurefire.excludedGroups=
 * -Dgroups=integration}
 */
@Tag("integration")
class SqlUdfIT {

    private static final String CHAT = "hf.co/LiquidAI/LFM2.5-350M-GGUF:Q8_0";
    private static final String EMBED = "hf.co/LiquidAI/LFM2.5-Embedding-350M-GGUF:Q8_0";

    @Test
    void labelsAreGrammarPinnedAndThePrefixIsCached() {
        Path chat = TestModels.require(CHAT);
        Path embed = TestModels.require(EMBED);
        try (JinferBackend backend = new JinferBackend(chat, embed, 1024)) {
            Llm.install(backend);
            String labels = "bug,feature,question";
            Set<String> allowed = Set.of("bug", "feature", "question");

            String first =
                    LlmFunctions.classify("The app crashes every time I open settings.", labels);
            String second =
                    LlmFunctions.classify("Could you add a dark mode to the editor?", labels);
            assertTrue(allowed.contains(first), first);
            assertTrue(allowed.contains(second), second);
            // the shared prefix: the second row's prompt was served its instruction from cache
            LlmBackend.Stats stats = backend.stats();
            assertEquals(2, stats.calls());
            assertTrue(
                    stats.restoredTokens() > 0,
                    "no prompt tokens restored across two rows: " + stats);

            Boolean refund =
                    LlmFunctions.judge(
                            "I was charged twice, please refund the second charge.",
                            "Is the customer asking for a refund?");
            assertNotNull(refund);

            Float[] a = LlmFunctions.embed("refund my order");
            Float[] b = LlmFunctions.embed("refund my order");
            assertTrue(a.length > 0);
            assertEquals(a.length, b.length);
            assertEquals(1.0, LlmFunctions.cosine(a, b), 1e-4);

            String completion = LlmFunctions.complete("The capital of France is");
            assertTrue(completion != null && !completion.isBlank());
        } finally {
            Llm.install(null);
        }
    }

    @Test
    void theDemoRunsOnTheRealModels() throws Exception {
        Path chat = TestModels.require(CHAT);
        Path embed = TestModels.require(EMBED);
        try (JinferBackend backend = new JinferBackend(chat, embed, 1024)) {
            Llm.install(backend);
            Main.Report report = Main.run(System.out);
            assertEquals(50, report.counts().values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(3, report.nearest().size());
            assertTrue(List.copyOf(report.refunds()).stream().allMatch(id -> id >= 1 && id <= 50));
        } finally {
            Llm.install(null);
        }
    }
}
