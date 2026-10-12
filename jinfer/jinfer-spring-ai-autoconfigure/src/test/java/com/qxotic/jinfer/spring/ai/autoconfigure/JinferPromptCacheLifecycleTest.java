package com.qxotic.jinfer.spring.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.qxotic.jinfer.cache.FrozenBlocks;
import com.qxotic.jinfer.chat.Role;
import com.qxotic.jinfer.spring.ai.JinferChatModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;

/**
 * The warm-up and compile hooks through the real auto-configuration, over the weightless test
 * provider ({@link AutoconfigureTestModelProvider}): no GGUF weights, no network, a real engine
 * and a real block tree. The template records what the warm-up pinned; the catalog on disk is
 * what the first boot compiled; a second boot proves it is mounted and left alone.
 */
class JinferPromptCacheLifecycleTest {

    @TempDir Path dir;

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JinferChatAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetProvider() {
        AutoconfigureTestModelProvider.reset();
    }

    private Path model() throws Exception {
        return AutoconfigureTestModelProvider.writeModel(dir.resolve("test.gguf"));
    }

    @Test
    void warmUpPinsEveryConfiguredPromptAfterTheModelLoads() throws Exception {
        Path model = model();
        Path fromFile = dir.resolve("persona.txt");
        Files.writeString(fromFile, "You are a support assistant.", StandardCharsets.UTF_8);
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-warmup[0]=You are terse, and precise.",
                        "spring.ai.jinfer.chat.prompt-cache-warmup[1]=file:" + fromFile)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(JinferChatModel.class);
                            JinferPromptCacheLifecycle lifecycle =
                                    context.getBean(JinferPromptCacheLifecycle.class);
                            assertThat(lifecycle.isRunning()).isTrue();
                            assertThat(lifecycle.persist()).isNull();
                            assertThat(AutoconfigureTestModelProvider.loads).isEqualTo(1);
                            // each entry went through the engine's native codec as a system
                            // prompt: a literal as given, a file: resource by its content
                            assertThat(systemPrompts())
                                    .containsExactly(
                                            "You are terse, and precise.",
                                            "You are a support assistant.");
                        });
    }

    @Test
    void warmUpRunsBeforeTheWebServerWouldStart() {
        // Boot's WebServerStartStopLifecycle is at DEFAULT_PHASE - 2048: a lower phase starts
        // first, so the prompts are prefilled before the first request can arrive
        assertThat(JinferPromptCacheLifecycle.PHASE)
                .isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
    }

    @Test
    void theFirstBootCompilesTheCatalogAndLaterBootsMountIt() throws Exception {
        Path model = model();
        Path catalog = dir.resolve("cache").resolve("prompts.jkv");
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-warmup[0]=You are terse.",
                        "spring.ai.jinfer.chat.prompt-cache-persist=" + catalog)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(JinferPromptCacheLifecycle.class).persist())
                                    .isEqualTo(catalog);
                            // written at start, right after the warm-up: the parent directory
                            // was created, the temp file is gone, the catalog is in place
                            assertThat(catalog).isRegularFile();
                            assertThat(catalog.resolveSibling("prompts.jkv.tmp")).doesNotExist();
                        });
        byte[] compiled = Files.readAllBytes(catalog);
        int persisted =
                FrozenBlocks.open(catalog, AutoconfigureTestModelProvider.SEED).blockCount();
        assertThat(persisted).as("the warm-up prompt's blocks").isGreaterThan(0);
        assertThat(Files.readAllBytes(catalog))
                .as("nothing is written at stop")
                .isEqualTo(compiled);

        // second boot, no warm-up: the catalog is mounted (an export holds its blocks) and is
        // never rewritten - a present catalog is served, not regenerated
        AutoconfigureTestModelProvider.reset();
        Path export = dir.resolve("export.jkv");
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-persist=" + catalog)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(systemPrompts()).isEmpty();
                            context.getBean(JinferChatModel.class).saveCachedPrompts(export);
                            assertThat(
                                            FrozenBlocks.open(
                                                            export,
                                                            AutoconfigureTestModelProvider.SEED)
                                                    .blockCount())
                                    .as("the first boot's prompts are mounted")
                                    .isEqualTo(persisted);
                        });
        assertThat(Files.readAllBytes(catalog)).as("left alone when present").isEqualTo(compiled);
    }

    @Test
    void noWarmUpMeansNothingToCompile() throws Exception {
        Path model = model();
        Path catalog = dir.resolve("prompts.jkv");
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-persist=" + catalog)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(catalog).as("an empty catalog would mount nothing").doesNotExist();
    }

    @Test
    void neitherPropertyMakesTheLifecycleANoOp() throws Exception {
        Path model = model();
        runner.withPropertyValues("spring.ai.jinfer.chat.model=" + model)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            JinferPromptCacheLifecycle lifecycle =
                                    context.getBean(JinferPromptCacheLifecycle.class);
                            assertThat(lifecycle.warmup()).isEmpty();
                            assertThat(lifecycle.persist()).isNull();
                            assertThat(systemPrompts()).isEmpty();
                        });
    }

    @Test
    void aDirectoryAsTheCatalogPathFailsTheBootBeforeTheModelLoads() throws Exception {
        Path model = model();
        Path directory = Files.createDirectory(dir.resolve("prompts.jkv"));
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-persist=" + directory)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "spring.ai.jinfer.chat.prompt-cache-persist is a"
                                                    + " directory");
                            assertThat(AutoconfigureTestModelProvider.loads)
                                    .as("refused before any weights load")
                                    .isZero();
                        });
    }

    @Test
    void aBlankOrMissingWarmUpEntryFailsTheBootByName() throws Exception {
        Path model = model();
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-warmup[0]=You are terse.",
                        "spring.ai.jinfer.chat.prompt-cache-warmup[1]=file:"
                                + dir.resolve("absent.txt"))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "spring.ai.jinfer.chat.prompt-cache-warmup[1] does"
                                                    + " not exist");
                        });
        runner.withPropertyValues(
                        "spring.ai.jinfer.chat.model=" + model,
                        "spring.ai.jinfer.chat.prompt-cache-warmup[0]= ")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining(
                                            "spring.ai.jinfer.chat.prompt-cache-warmup[0] must"
                                                    + " not be blank");
                        });
    }

    /** The text of every system message the template was asked to encode, in order. */
    private static List<String> systemPrompts() {
        return AutoconfigureTestModelProvider.conversations.stream()
                .flatMap(conversation -> conversation.messages().stream())
                .filter(message -> message.role() == Role.SYSTEM)
                .map(message -> message.text())
                .toList();
    }
}
