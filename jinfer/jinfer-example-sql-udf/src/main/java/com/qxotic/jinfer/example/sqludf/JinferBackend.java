package com.qxotic.jinfer.example.sqludf;

import com.qxotic.jinfer.Arenas;
import com.qxotic.jinfer.ContextState;
import com.qxotic.jinfer.cache.PromptCache;
import com.qxotic.jinfer.chat.ChatEngine;
import com.qxotic.jinfer.chat.Conversation;
import com.qxotic.jinfer.chat.LoadedEmbedder;
import com.qxotic.jinfer.chat.Message;
import com.qxotic.jinfer.chat.Models;
import com.qxotic.jinfer.llm.Grammar;
import com.qxotic.jinfer.llm.Sampling;
import com.qxotic.jota.memory.MemoryAllocators;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine behind the SQL functions: one {@link ChatEngine} for completions and labels, one
 * {@link LoadedEmbedder} for vectors, each mapped on first use and freed at {@link #close}.
 *
 * <p>Labels are a grammar, not a parse: {@link #choose} hands the engine {@code root ::= "bug" |
 * "feature" | "question"} and the sampler masks every token that cannot continue one of those
 * literals, so free text is unrepresentable - the label comes back as its exact bytes, or the
 * engine fails loudly. Greedy decoding (temperature 0) makes the answer a deterministic function of
 * the prompt.
 *
 * <p>Shared prefix: the instruction (per label set, per question) is defined into the engine's
 * prompt cache the first time it is seen. Every row's prompt then starts with a prefix the block
 * layer already holds, so a query over N rows pays the instruction once and re-ingests only each
 * row's text. The decode is still one pass per row - jinfer's engine serializes generations and
 * exposes no public multi-sequence (tree-attention) decode, see the README.
 */
final class JinferBackend implements LlmBackend {

    /** Labels are short; the grammar ends the reply at the literal's last byte anyway. */
    private static final int LABEL_TOKEN_BUDGET = 32;

    private final Path chatModel;
    private final Path embeddingModel;
    private final int contextCapacity;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Set<String> definedPrefixes = new HashSet<>();

    private ChatEngine engine; // lazy, under lock
    private Arena embeddingArena; // lazy, under lock; owns the embedder's weights and state
    private LoadedEmbedder<?> embedder;
    private ContextState embeddingState;
    private Stats stats = Stats.NONE;
    private boolean closed;

    /**
     * Paths, never references: resolving a reference (downloading, cache lookup) is {@link Llm}'s
     * job, done before this constructor, so a backend built in a test from a cached fixture never
     * touches the network.
     */
    JinferBackend(Path chatModel, Path embeddingModel, int contextCapacity) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        if (contextCapacity <= 0) {
            throw new IllegalArgumentException("contextCapacity " + contextCapacity);
        }
        this.contextCapacity = contextCapacity;
    }

    @Override
    public String complete(String prompt, int maxTokens) {
        Objects.requireNonNull(prompt, "prompt");
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens " + maxTokens);
        lock.lock();
        try {
            ChatEngine chat = chat();
            ChatEngine.Request request =
                    ChatEngine.Request.builder(List.of(Message.user(prompt)), greedy(chat))
                            .thinking(false)
                            .maxOutputTokens(maxTokens)
                            .build();
            return record(chat.complete(request)).reply().text().strip();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String choose(String instruction, String text, List<String> labels) {
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(text, "text");
        if (labels.isEmpty()) throw new IllegalArgumentException("no labels");
        lock.lock();
        try {
            ChatEngine chat = chat();
            Message system = Message.system(instruction);
            definePrefix(chat, instruction, system);
            ChatEngine.Request request =
                    ChatEngine.Request.builder(List.of(system, Message.user(text)), greedy(chat))
                            .thinking(false)
                            .maxOutputTokens(LABEL_TOKEN_BUDGET)
                            .grammar(labelGrammar(labels))
                            .build();
            String reply = record(chat.complete(request)).reply().text().strip();
            if (!labels.contains(reply)) {
                // unreachable under the grammar; a budget cut mid-literal is the one way here
                throw new IllegalStateException(
                        "constrained reply '" + reply + "' is not one of " + labels);
            }
            return reply;
        } finally {
            lock.unlock();
        }
    }

    /** {@code root ::= "a" | "b" | ...}: the closed label set as byte literals. */
    static String labelGrammar(List<String> labels) {
        StringJoiner alternatives = new StringJoiner(" | ", "root ::= ", "");
        for (String label : labels) alternatives.add(Grammar.gbnfLiteral(label));
        return alternatives.toString();
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text");
        lock.lock();
        try {
            LoadedEmbedder<?> model = embedder();
            // the card's document framing on every row: one convention for both sides of a
            // row-to-row cosine, and the framing the model was trained with ("" when none)
            List<float[]> out = new ArrayList<>(1);
            model.embedAll(embeddingState, List.of(model.documentPrefix() + text), out::add);
            return out.get(0);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Stats stats() {
        lock.lock();
        try {
            return stats;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resetStats() {
        lock.lock();
        try {
            stats = Stats.NONE;
        } finally {
            lock.unlock();
        }
    }

    /** Idempotent; frees the chat engine, then the embedder's state and weights. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            try {
                if (engine != null) engine.close();
            } finally {
                if (embeddingState != null) embeddingState.close();
                if (embeddingArena != null) Arenas.close(embeddingArena);
            }
        } finally {
            lock.unlock();
        }
    }

    private ChatEngine chat() {
        if (closed) throw new IllegalStateException("backend closed");
        if (engine == null) {
            engine =
                    new ChatEngine(
                            chatModel,
                            Map.of(),
                            PromptCache.Options.DEFAULTS.withContextCapacity(contextCapacity));
        }
        return engine;
    }

    private LoadedEmbedder<?> embedder() {
        if (closed) throw new IllegalStateException("backend closed");
        if (embedder == null) {
            Arena arena = Arenas.newCrossThread();
            try {
                LoadedEmbedder<?> loaded = Models.loadEmbedder(embeddingModel, arena);
                int capacity =
                        Math.min(
                                contextCapacity, loaded.model().configuration().maxContextLength());
                embeddingState = newState(loaded, capacity, arena);
                embedder = loaded;
                embeddingArena = arena;
            } catch (IOException e) {
                Arenas.close(arena);
                throw new UncheckedIOException("failed to load " + embeddingModel, e);
            } catch (RuntimeException | Error e) {
                Arenas.close(arena);
                throw e;
            }
        }
        return embedder;
    }

    /**
     * The state borrows the arena the weights live in (the idiom of the Spring AI adapter's
     * embedding model), so {@link #close} frees both deterministically: state first, then arena.
     */
    private static <S extends ContextState> S newState(
            LoadedEmbedder<S> loaded, int capacity, Arena arena) {
        return loaded.model().newState(capacity, MemoryAllocators.ofArena(arena));
    }

    /**
     * Pins the instruction turn into the block layer once per distinct instruction. Skipped when
     * the model has no native chat-template codec (then the engine has no block layer and every row
     * would prefill the whole prompt - correct, just slower).
     *
     * <p>{@code definePrompt(Conversation)} rather than the {@code definePrompt(Request)} the engine
     * recommends: the Request form exists because the thinking floor can render a different prompt
     * than the flag alone, and here thinking is pinned false on this define and on every request
     * that follows, so both forms encode the same prefix.
     */
    private void definePrefix(ChatEngine chat, String instruction, Message system) {
        if (!chat.blockCaching() || !definedPrefixes.add(instruction)) return;
        chat.definePrompt(new Conversation(List.of(system), List.of(), false));
    }

    /** Temperature 0: the argmax token every step, so a label is a function of its prompt. */
    private static Sampling greedy(ChatEngine chat) {
        return chat.loaded().samplingDefaults().resolve(0f, null, null, null, null);
    }

    private ChatEngine.Completion record(ChatEngine.Completion completion) {
        if (completion.cancelled()) throw new IllegalStateException("generation cancelled");
        var result = completion.result();
        stats =
                stats.plus(
                        completion.promptTokens(),
                        completion.restoredTokens(),
                        result.completionTokens(),
                        result.promptTime().toNanos(),
                        result.decodeTime().toNanos());
        return completion;
    }
}
