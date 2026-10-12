package com.qxotic.jinfer.spring.ai.autoconfigure;

import com.qxotic.jinfer.codecs.VideoSampler;
import com.qxotic.jinfer.hub.ModelStore;
import com.qxotic.jinfer.spring.ai.JinferChatModel;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * Wires one {@link JinferChatModel} bean from {@code spring.ai.jinfer.chat.*} properties.
 *
 * <p>Active when {@code spring.ai.model.chat=jinfer} selects jinfer explicitly, or when no chat
 * provider is selected and {@code spring.ai.jinfer.chat.model} is set:
 *
 * <pre>{@code
 * spring.ai.jinfer.chat.model=unsloth/gemma-4-E2B-it-GGUF:Q4_K_M
 * spring.ai.jinfer.chat.temperature=0.7
 * }</pre>
 *
 * <p>The prompt cache has three properties: {@code prompt-cache} mounts an artifact read-only;
 * {@code prompt-cache-warmup} prefills prompts once the model has loaded; {@code
 * prompt-cache-persist} names a catalog holding those warm-up prompts' state, mounted when it
 * exists and compiled once at startup when it does not. The last two run through {@link
 * JinferPromptCacheLifecycle}.
 */
@AutoConfiguration
@ConditionalOnClass(JinferChatModel.class)
@Conditional(JinferChatSelected.class)
@EnableConfigurationProperties(JinferChatProperties.class)
public class JinferChatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JinferChatModel jinferChatModel(
            JinferChatProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<VideoSampler> videoSampler) {
        if (!StringUtils.hasText(properties.model())) {
            throw new IllegalStateException(
                    "spring.ai.jinfer.chat.model is required: a local GGUF path, or a model ref"
                            + " (unsloth/gemma-4-E2B-it-GGUF:Q4_K_M)");
        }
        JinferChatModel.Builder builder =
                JinferChatModel.builder()
                        .retainSessions(properties.retainedSessions())
                        .options(properties.toOptions())
                        .speculationDepth(properties.speculationDepth());
        if (properties.contextCapacity() != null)
            builder.contextCapacity(properties.contextCapacity());
        if (properties.model().contains("://")) {
            throw new IllegalStateException(
                    "spring.ai.jinfer.chat.model is a URL; download it first and configure its"
                            + " local path");
        } else if (ModelStore.isRef(properties.model())) {
            builder.model(properties.model());
        } else {
            builder.modelPath(Path.of(properties.model())); // local path: the explicit door
        }
        observationRegistry.ifAvailable(builder::observationRegistry);
        observationConvention.ifAvailable(builder::observationConvention);
        videoSampler.ifAvailable(builder::videoSampler); // a VideoSampler bean overrides UNIFORM
        if (properties.companions() != null) {
            properties
                    .companions()
                    .forEach(
                            (capability, value) -> {
                                if (!StringUtils.hasText(value)) {
                                    throw new IllegalStateException(
                                            "spring.ai.jinfer.chat.companions."
                                                    + capability
                                                    + " must not be blank");
                                }
                                if (value.contains("://")) {
                                    throw new IllegalStateException(
                                            "spring.ai.jinfer.chat.companions."
                                                    + capability
                                                    + " is a URL; download it first and configure"
                                                    + " its local path");
                                }
                                if (ModelStore.isRef(value)) {
                                    builder.companion(capability, value);
                                } else {
                                    builder.companionPath(capability, Path.of(value));
                                }
                            });
        }
        boolean mount = StringUtils.hasText(properties.promptCache());
        boolean persist = StringUtils.hasText(properties.promptCachePersist());
        if (mount && persist) {
            // the model mounts ONE catalog, and the two properties disagree about writing it
            throw new IllegalStateException(
                    "spring.ai.jinfer.chat.prompt-cache and prompt-cache-persist are both set:"
                            + " prompt-cache is a read-only mount, prompt-cache-persist a catalog"
                            + " the application rewrites at shutdown - configure one");
        }
        if (mount) {
            builder.promptCache(Path.of(properties.promptCache()));
        } else if (persist) {
            // an unusable path fails here, before the weights load, not after the warm-up
            Path catalog =
                    JinferPromptCacheLifecycle.requireCatalogPath(properties.promptCachePersist());
            // the catalog compiled by an earlier boot serves this one; absent, the lifecycle
            // compiles it once after the warm-up
            if (Files.isRegularFile(catalog)) builder.promptCache(catalog);
        }
        return builder.build();
    }

    /**
     * Prefills {@code prompt-cache-warmup} after the model loads and compiles {@code
     * prompt-cache-persist} when it is absent; a no-op bean when neither is set.
     */
    @Bean
    @ConditionalOnMissingBean
    public JinferPromptCacheLifecycle jinferPromptCacheLifecycle(
            JinferChatModel model, JinferChatProperties properties, ResourceLoader resources) {
        return new JinferPromptCacheLifecycle(model, properties, resources);
    }
}
