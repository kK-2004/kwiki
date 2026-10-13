package com.kwiki.infrastructure.ai.registry;

import com.kwiki.graph.model.RateLimiter;
import com.kwiki.infrastructure.observability.SecretRedaction;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 以不可变快照承载模型配置的注册表。reload 读库、解密、构建，最后原子替换快照；
 * 任何一步失败都保留旧快照并记录脱敏错误。客户端按 {@link ClientSpec#fingerprint()} 复用，
 * 限流器按 provider 与限流参数复用，输出上限探测结果按 baseUrl + 模型名缓存。
 */
@Component
public class DefaultModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelRegistry.class);

    private final ModelConfigStore store;
    private final ModelSecretCipher cipher;
    private final ModelClientFactory factory;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    private final Map<String, Optional<Integer>> probedLimits = new ConcurrentHashMap<>();
    private volatile String lastError;

    public DefaultModelRegistry(ModelConfigStore store, ModelSecretCipher cipher, ModelClientFactory factory) {
        this.store = store;
        this.cipher = cipher;
        this.factory = factory;
    }

    /** 重新加载目录；成功返回 true。 */
    public synchronized boolean reload() {
        try {
            ModelCatalog catalog = store.load();
            Snapshot next = build(catalog, snapshot.get());
            snapshot.set(next);
            lastError = null;
            log.info("model config loaded version={} purposes={} embeddings={}",
                    catalog.version(), next.entries.size(), next.embeddings.size());
            return true;
        } catch (Exception e) {
            lastError = SecretRedaction.redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.error("model config reload failed, keeping version {}: {}", snapshot.get().version, lastError);
            return false;
        }
    }

    public long loadedVersion() {
        return snapshot.get().version;
    }

    @Override
    public ChatModel chat(ModelPurpose purpose) {
        return entry(purpose).chat;
    }

    @Override
    public StreamingChatModel streaming(ModelPurpose purpose) {
        return entry(purpose).streaming;
    }

    @Override
    public ResolvedModel resolve(ModelPurpose purpose) {
        return entry(purpose).resolved;
    }

    @Override
    public Optional<EmbeddingBinding> embedding(String providerName, String modelName) {
        return Optional.ofNullable(snapshot.get().embeddings.get(providerName + "\u0000" + modelName));
    }

    @Override
    public List<EmbeddingBinding> embeddings() {
        return List.copyOf(snapshot.get().embeddings.values());
    }

    @Override
    public Status status() {
        Snapshot current = snapshot.get();
        return new Status(current.version, current.loadedAt, lastError);
    }

    private Entry entry(ModelPurpose purpose) {
        Entry entry = snapshot.get().entries.get(purpose);
        if (entry == null) {
            throw new ModelNotConfiguredException(purpose);
        }
        return entry;
    }

    private Snapshot build(ModelCatalog catalog, Snapshot previous) {
        Map<Long, String> keys = new HashMap<>();
        for (ModelProvider provider : catalog.providers()) {
            if (!provider.enabled()) continue;
            try {
                keys.put(provider.id(), cipher.decrypt(provider.apiKeyCipher()));
            } catch (IllegalStateException e) {
                throw new IllegalStateException("provider「" + provider.name() + "」" + e.getMessage());
            }
        }
        Map<String, ChatModel> chats = new HashMap<>();
        Map<String, StreamingChatModel> streams = new HashMap<>();
        Map<String, RateLimiter> limiters = new HashMap<>();
        Map<ModelPurpose, Entry> entries = new EnumMap<>(ModelPurpose.class);
        for (ModelAssignment assignment : catalog.assignments().values()) {
            if (assignment.modelId() == null) continue;
            ModelDefinition model = catalog.model(assignment.modelId()).orElse(null);
            if (model == null || !model.enabled() || !model.has(assignment.purpose().requiredTag())) continue;
            ModelProvider provider = catalog.provider(model.providerId()).orElse(null);
            if (provider == null || !provider.enabled()) continue;
            String apiKey = keys.get(provider.id());
            Map<String, Object> extra = ModelExtra.merge(model.extra(), assignment.extra());
            Duration timeout = timeout(provider, extra);
            Integer maxTokens = assignment.maxTokens() != null ? assignment.maxTokens()
                    : model.maxOutputTokens() != null ? model.maxOutputTokens()
                    : probe(provider, apiKey, timeout, model);
            double temperature = assignment.temperature() != null
                    ? assignment.temperature() : assignment.purpose().defaultTemperature();
            ClientSpec spec = new ClientSpec(provider.baseUrl(), apiKey, timeout, model.modelName(),
                    temperature, maxTokens, assignment.thinking(), ModelExtra.passthrough(extra));
            String fingerprint = spec.fingerprint();
            RateLimiter limiter = provider.maxConcurrency() == null ? null
                    : limiters.computeIfAbsent(provider.id() + ":" + provider.maxConcurrency() + ":" + provider.qps(),
                            key -> previous.limiters.getOrDefault(key,
                                    new RateLimiter(provider.maxConcurrency(), provider.qps() == null ? 0 : provider.qps())));
            ChatModel chat = null;
            StreamingChatModel streaming = null;
            if (assignment.purpose().requiredTag() == ModelTag.CHAT) {
                chat = chats.computeIfAbsent(fingerprint,
                        key -> previous.chats.containsKey(key) ? previous.chats.get(key) : factory.chat(spec));
                streaming = streams.computeIfAbsent(fingerprint,
                        key -> previous.streams.containsKey(key) ? previous.streams.get(key) : factory.streaming(spec));
            }
            ResolvedModel resolved = new ResolvedModel(assignment.purpose(), provider.id(), provider.name(),
                    provider.baseUrl(), apiKey, model.modelName(), temperature, maxTokens, assignment.thinking(),
                    provider.maxRetries(), timeout, limiter, provider.maxConcurrency(), extra);
            entries.put(assignment.purpose(), new Entry(resolved, chat, streaming));
        }
        Map<String, EmbeddingBinding> embeddings = new LinkedHashMap<>();
        Map<String, EmbeddingBinding> embeddingsByFingerprint = new HashMap<>();
        for (ModelDefinition model : catalog.models()) {
            if (!model.enabled() || !model.has(ModelTag.EMBEDDING) || model.dimensions() == null) continue;
            ModelProvider provider = catalog.provider(model.providerId()).orElse(null);
            if (provider == null || !provider.enabled()) continue;
            String apiKey = keys.get(provider.id());
            Duration timeout = timeout(provider, model.extra());
            Integer configuredBatch = ModelExtra.intValue(model.extra(), ModelExtra.BATCH_SIZE);
            int batchSize = configuredBatch == null ? ModelExtra.DEFAULT_BATCH_SIZE : configuredBatch;
            Map<String, Object> parameters = ModelExtra.passthrough(model.extra());
            String fingerprint = new ClientSpec(provider.baseUrl(), apiKey, timeout, model.modelName(),
                    model.dimensions(), batchSize, null, parameters).fingerprint()
                    + ":" + provider.name() + ":" + provider.maxRetries();
            EmbeddingBinding binding = previous.embeddingsByFingerprint.get(fingerprint);
            if (binding == null) {
                binding = new EmbeddingBinding(provider.name(), model.modelName(), model.dimensions(),
                        factory.embedding(provider.baseUrl(), apiKey, timeout, model.modelName(),
                                model.dimensions(), provider.maxRetries(), batchSize, parameters));
            }
            embeddingsByFingerprint.put(fingerprint, binding);
            embeddings.put(provider.name() + "\u0000" + model.modelName(), binding);
        }
        return new Snapshot(catalog.version(), Instant.now(), entries, chats, streams, limiters,
                embeddings, embeddingsByFingerprint);
    }

    /** extra.timeoutMs 覆盖 provider 的请求超时。 */
    private static Duration timeout(ModelProvider provider, Map<String, Object> extra) {
        Integer override = ModelExtra.intValue(extra, ModelExtra.TIMEOUT_MS);
        return Duration.ofMillis(override == null ? provider.requestTimeoutMs() : override);
    }

    private Integer probe(ModelProvider provider, String apiKey, Duration timeout, ModelDefinition model) {
        String key = provider.baseUrl() + "\u0000" + model.modelName();
        return probedLimits.computeIfAbsent(key, k -> {
            try {
                return Optional.ofNullable(factory.probeOutputLimit(provider.baseUrl(), apiKey, timeout, model.modelName()));
            } catch (Exception e) {
                log.debug("output limit probe failed model={}: {}", model.modelName(),
                        SecretRedaction.redact(e.getMessage()));
                return Optional.empty();
            }
        }).orElse(null);
    }

    private record Entry(ResolvedModel resolved, ChatModel chat, StreamingChatModel streaming) {
    }

    private record Snapshot(long version, Instant loadedAt, Map<ModelPurpose, Entry> entries,
                            Map<String, ChatModel> chats, Map<String, StreamingChatModel> streams,
                            Map<String, RateLimiter> limiters, Map<String, EmbeddingBinding> embeddings,
                            Map<String, EmbeddingBinding> embeddingsByFingerprint) {

        static final Snapshot EMPTY = new Snapshot(0, null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of());

        Snapshot {
            entries = Map.copyOf(entries);
            chats = Map.copyOf(chats);
            streams = Map.copyOf(streams);
            limiters = Map.copyOf(limiters);
            embeddings = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(embeddings));
            embeddingsByFingerprint = Map.copyOf(embeddingsByFingerprint);
        }
    }

    /** 供管理端展示：当前快照中每个用途实际生效的模型。 */
    public List<ResolvedModel> resolvedAll() {
        return new ArrayList<>(snapshot.get().entries.values().stream().map(Entry::resolved).toList());
    }
}
