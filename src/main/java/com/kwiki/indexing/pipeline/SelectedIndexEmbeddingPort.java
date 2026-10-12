package com.kwiki.indexing.pipeline;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.infrastructure.ai.registry.EmbeddingBinding;
import com.kwiki.infrastructure.ai.registry.ModelRegistry;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 查询侧与社区索引使用的 embedding 入口：按已发布（selected）索引版本记录的
 * provider 名 + 模型名，委托给模型目录中对应的客户端，保证查询向量与索引向量
 * 处于同一语义空间。已发布版本按 10 秒缓存；尚无已发布版本时（首次部署引导前）
 * 退回目录中 default provider 的第一个 embedding 模型。
 */
@Component
public class SelectedIndexEmbeddingPort implements ChunkEmbeddingPort {

    private static final long CACHE_NANOS = Duration.ofSeconds(10).toNanos();

    private final ModelRegistry models;
    private final SearchIndexVersionRepository versions;
    private final LongSupplier nanoTime;
    private volatile Selected cached;

    @org.springframework.beans.factory.annotation.Autowired
    public SelectedIndexEmbeddingPort(ModelRegistry models, SearchIndexVersionRepository versions) {
        this(models, versions, System::nanoTime);
    }

    SelectedIndexEmbeddingPort(ModelRegistry models, SearchIndexVersionRepository versions, LongSupplier nanoTime) {
        this.models = models;
        this.versions = versions;
        this.nanoTime = nanoTime;
    }

    @Override
    public String cacheIdentity() {
        return binding().map(EmbeddingBinding::identity).orElse("unavailable");
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return binding().orElseThrow(() -> new IllegalStateException(
                        "已发布索引版本引用的 embedding 模型在模型目录中不可用"))
                .port().embed(texts);
    }

    /** 当前生效的 embedding 模型；用于首次部署引导选择默认模型。 */
    public Optional<EmbeddingBinding> binding() {
        Selected selected = selected();
        if (selected.provider() != null) {
            return models.embedding(selected.provider(), selected.model())
                    .filter(b -> b.dimensions() == selected.dimensions());
        }
        return defaultBinding(models);
    }

    /** 目录中 default provider 的第一个 embedding 模型，其次任意一个。 */
    public static Optional<EmbeddingBinding> defaultBinding(ModelRegistry models) {
        return models.embeddings().stream()
                .min(Comparator.comparing((EmbeddingBinding b) ->
                        !IndexingProperties.DEFAULT_EMBEDDING_PROFILE.equals(b.providerName())));
    }

    private Selected selected() {
        Selected current = cached;
        long now = nanoTime.getAsLong();
        if (current != null && now - current.loadedAt() < CACHE_NANOS) {
            return current;
        }
        Optional<SearchIndexVersion> version = versions.findBySelectedTrue();
        Selected next = version
                .map(SearchIndexVersion::editableConfig)
                .map(c -> new Selected(c.embeddingProvider(), c.embeddingModel(), c.embeddingDimensions(), now))
                .orElse(new Selected(null, null, 0, now));
        cached = next;
        return next;
    }

    private record Selected(String provider, String model, int dimensions, long loadedAt) {
    }
}
