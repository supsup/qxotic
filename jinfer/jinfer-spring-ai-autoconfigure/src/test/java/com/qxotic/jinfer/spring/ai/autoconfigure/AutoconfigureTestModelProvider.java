package com.qxotic.jinfer.spring.ai.autoconfigure;

import com.qxotic.format.gguf.Builder;
import com.qxotic.format.gguf.GGUF;
import com.qxotic.jinfer.Batch;
import com.qxotic.jinfer.ContentKey;
import com.qxotic.jinfer.chat.ChatTemplate;
import com.qxotic.jinfer.chat.Conversation;
import com.qxotic.jinfer.chat.LoadedModel;
import com.qxotic.jinfer.chat.Message;
import com.qxotic.jinfer.chat.ModelProvider;
import com.qxotic.jinfer.chat.ReplyParser;
import com.qxotic.jinfer.testkit.TestLanguageModel;
import com.qxotic.toknroll.IntSequence;
import com.qxotic.toknroll.Tokenizer;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test-classpath only: the real GGUF dispatch ({@code Models.load} through {@code
 * META-INF/services}) landing on the testkit's weightless, cacheable model, so the auto-configured
 * {@code JinferChatModel} bean - and the cache lifecycle behind it - runs end to end without
 * weights or a download. The template records every conversation it encodes, which is how a test
 * sees what the warm-up pinned. Same shape as the CLI's test provider.
 */
public final class AutoconfigureTestModelProvider implements ModelProvider {

    public static final String ARCHITECTURE = "spring_ai_test_language";

    /** The cache identity every model this provider loads carries - what a catalog is keyed by. */
    public static final ContentKey SEED = new ContentKey("spring-ai-autoconfigure-test");

    /** Every conversation the template encoded, across loads; tests clear it. */
    static final List<Conversation> conversations = new CopyOnWriteArrayList<>();

    static int loads;

    static void reset() {
        conversations.clear();
        loads = 0;
    }

    /** Writes a metadata-only GGUF this provider claims; a new file, never overwritten. */
    static Path writeModel(Path file) throws IOException {
        GGUF.write(
                Builder.newBuilder().putString("general.architecture", ARCHITECTURE).build(), file);
        return file;
    }

    @Override
    public Set<String> architectures() {
        return Set.of(ARCHITECTURE);
    }

    @Override
    public LoadedModel<?> loadLanguage(
            FileChannel channel,
            GGUF gguf,
            Path path,
            Arena arena,
            Map<String, Path> companions,
            Tokenizer tokenizer) {
        loads++;
        return new LoadedModel<>(
                new TestLanguageModel(),
                tokenizer == null ? TestLanguageModel.TOKENIZER : tokenizer,
                "",
                Set.of(),
                SEED,
                Optional.of(new Template()),
                LoadedModel.SamplingDefaults.NONE);
    }

    /** One prefill batch per conversation, rendered as role:text lines over the byte tokenizer. */
    static final class Template implements ChatTemplate {
        @Override
        public ReplyState encode(Conversation conversation, int capacity, Consumer<Batch> sink) {
            conversations.add(conversation);
            StringBuilder text = new StringBuilder("think:" + conversation.thinking() + '\n');
            for (Message message : conversation.messages()) {
                text.append(message.role()).append(':').append(message.text()).append('\n');
            }
            sink.accept(Batch.prefill(TestLanguageModel.TOKENIZER.encode(text).toArray()));
            return new ReplyState(
                    IntSequence.empty(), ReplyParser.spans(TestLanguageModel.TOKENIZER));
        }

        @Override
        public ThinkingPolicy thinkingPolicy() {
            return ThinkingPolicy.NONE;
        }
    }
}
