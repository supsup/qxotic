package com.qxotic.jinfer.spring.ai.autoconfigure;

import com.qxotic.jinfer.spring.ai.JinferChatModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * The prompt cache's application-lifecycle hook, driven by {@code spring.ai.jinfer.chat.*}: the
 * prompt compiler as a Spring bean.
 *
 * <ul>
 *   <li>WARM-UP ({@code prompt-cache-warmup}) runs at {@link #start()}, once every singleton exists
 *       (the model is loaded) and BEFORE the web server starts (see {@link #getPhase()}), so the
 *       first request to arrive already finds the prompts prefilled. Each entry is pinned the way
 *       {@link JinferChatModel#withCachedPrompt} pins a system prompt: a conversation opening with
 *       it restores the prefix instead of recomputing it.
 *   <li>COMPILE ({@code prompt-cache-persist}) runs right after the warm-up, only when the catalog
 *       does not exist yet: the block layer - which at that point holds exactly the warm-up
 *       prompts, never a conversation - is exported to a sibling temp file and moved into place
 *       atomically. Nothing is mapped yet, so the move is safe on every platform, and a crash
 *       mid-write leaves no catalog rather than a torn one. When the catalog exists the
 *       auto-configuration mounts it read-only and this bean leaves it alone: a changed warm-up or
 *       model is not detected, deleting the file regenerates it.
 * </ul>
 *
 * <p>Nothing happens at {@link #stop()}; the mounted catalog is released with the model bean.
 */
public final class JinferPromptCacheLifecycle implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(JinferPromptCacheLifecycle.class);

    /**
     * Started before Boot's web server: {@code WebServerStartStopLifecycle} runs at {@code
     * DEFAULT_PHASE - 2048}, so a phase below that puts the warm-up ahead of the first request.
     * Stopped after it, correspondingly, though stop does nothing here.
     */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final String PERSIST_PROPERTY = "spring.ai.jinfer.chat.prompt-cache-persist";

    private final JinferChatModel model;
    private final List<String> warmup;
    private final Path persist;
    private final ResourceLoader resources;
    private volatile boolean running;

    JinferPromptCacheLifecycle(
            JinferChatModel model, JinferChatProperties properties, ResourceLoader resources) {
        this.model = model;
        this.warmup =
                properties.promptCacheWarmup() == null
                        ? List.of()
                        : List.copyOf(properties.promptCacheWarmup());
        this.persist =
                StringUtils.hasText(properties.promptCachePersist())
                        ? requireCatalogPath(properties.promptCachePersist())
                        : null;
        this.resources = resources;
    }

    /**
     * The {@code prompt-cache-persist} value as a path this bean can write: not a directory, and
     * under a parent that exists (created here) and is writable. Checked at construction, before
     * any weights load, so a bad path fails the boot by name instead of the first compile.
     */
    static Path requireCatalogPath(String value) {
        Path path = Path.of(value);
        if (Files.isDirectory(path)) {
            throw new IllegalStateException(PERSIST_PROPERTY + " is a directory: " + path);
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException(
                        PERSIST_PROPERTY + ": parent directory cannot be created: " + parent, e);
            }
            if (!Files.isWritable(parent)) {
                throw new IllegalStateException(
                        PERSIST_PROPERTY + ": parent directory is not writable: " + parent);
            }
        }
        return path;
    }

    /** The prompts the warm-up pins, as configured (resource locations unresolved). */
    List<String> warmup() {
        return warmup;
    }

    /** The catalog compiled at the first boot and mounted by later ones, or null. */
    Path persist() {
        return persist;
    }

    @Override
    public void start() {
        if (running) return;
        List<String> prompts = resolve(warmup);
        for (String prompt : prompts) {
            // the view is discarded: defining is the side effect wanted, the prefix now lives
            // in the engine's block tree and any request opening with it restores it
            model.withCachedPrompt(List.of(new SystemMessage(prompt)), List.of());
        }
        if (!prompts.isEmpty()) {
            LOG.info("prompt cache warmed with " + prompts.size() + " prompt(s)");
        }
        if (persist != null && !Files.isRegularFile(persist)) {
            if (prompts.isEmpty()) {
                LOG.info(PERSIST_PROPERTY + ": no warm-up prompts to compile into " + persist);
            } else {
                compile(prompts.size());
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Each warm-up entry as prompt text: a Spring resource location ({@code file:}, {@code
     * classpath:}) is read as UTF-8, anything else is the prompt itself. A blank entry or a missing
     * resource fails the start by name: a warm-up that silently pinned nothing would make every
     * first request recompute with no diagnostic.
     */
    private List<String> resolve(List<String> entries) {
        List<String> prompts = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            String property = "spring.ai.jinfer.chat.prompt-cache-warmup[" + i + "]";
            if (!StringUtils.hasText(entry)) {
                throw new IllegalStateException(property + " must not be blank");
            }
            if (isResourceLocation(entry)) {
                Resource resource = resources.getResource(entry);
                if (!resource.exists()) {
                    throw new IllegalStateException(property + " does not exist: " + entry);
                }
                try {
                    prompts.add(resource.getContentAsString(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(property + " could not be read: " + entry, e);
                }
            } else {
                prompts.add(entry);
            }
        }
        return prompts;
    }

    /**
     * Only an explicit Spring prefix names a resource: a bare path would be indistinguishable from
     * a prompt that happens to look like one.
     */
    private static boolean isResourceLocation(String entry) {
        return entry.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX) || entry.startsWith("file:");
    }

    /**
     * Export to a sibling temp file, then an atomic move into place. The temp file sits next to the
     * catalog because a move across file systems is a copy, not atomic. The JFR store event names
     * the temp path (it is emitted by the export); the final path is logged here.
     */
    private void compile(int prompts) {
        Path temp = persist.resolveSibling(persist.getFileName() + ".tmp");
        try {
            model.saveCachedPrompts(temp);
            Files.move(
                    temp,
                    persist,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            LOG.info("prompt cache compiled " + prompts + " warm-up prompt(s) into " + persist);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IllegalStateException(PERSIST_PROPERTY + ": failed to write " + persist, e);
        }
    }
}
